package fi.csc.chipster.auth.resource;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.DELETE;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path("tokens")
public class TokenResource {

	public static final String TOKENS = "tokens";

	private static final String TOKEN_HEADER = "chipster-token";

	private TokenTable tokenTable;
	private UserTable userTable;

	public TokenResource(TokenTable tokenTable, UserTable userTable) {
		this.tokenTable = tokenTable;
		this.userTable = userTable;
	}

	@POST
	@RolesAllowed(Role.PASSWORD)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response createToken(@Context SecurityContext sc) {

		// curl -i -H "Content-Type: application/json" --user client:clientPassword -X POST http://localhost:8081/auth/tokens

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
		String username = sc.getUserPrincipal().getName();

		if (username == null) {
			// RolesAllowed prevents this
			throw new NotAuthorizedException("username is null");
		}
		
		Token token = tokenTable.createAndSaveToken(username, principal.getRoles());
		token.setName(this.getName(token, principal.getRoles() ));
		
		return Response.ok(token).build();
	}

	@GET
	@RolesAllowed(Role.SERVER)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response checkToken(@HeaderParam(TOKEN_HEADER) String requestToken, @Context SecurityContext sc) {

		Token dbToken = tokenTable.getToken(requestToken);

		if (dbToken.getValidUntil().isAfter(Instant.now())) {
			dbToken.setName(this.getName(dbToken, ((AuthPrincipal)sc.getUserPrincipal()).getRoles()));
			return Response.ok(dbToken).build();
		} else {
			// not a ForbiddenException because the server's token was authenticated correctly in the TokenRequestFilter 
			throw new NotFoundException("token expired");
		}				
	}	

	@POST
	@Path("{refresh}")
	@RolesAllowed({Role.CLIENT, Role.SERVER})
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response refreshToken(@Context SecurityContext sc) {

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
		String token = principal.getTokenKey();

		Token dbToken = tokenTable.refreshToken(token);
		dbToken.setName(this.getName(dbToken, principal.getRoles()));
		
		return Response.ok(dbToken).build();
	}

	@GET
	@Path("{check}")
	@RolesAllowed({Role.CLIENT, Role.SERVER})
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response checkClientToken(@Context SecurityContext sc) {

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
		String token = principal.getTokenKey();

		Token dbToken;
		try {
			dbToken = tokenTable.getToken(token);
		} catch (NotFoundException nfe) {
			throw new ForbiddenException("token not found");
		}
		
		// throws forbidden
		tokenTable.failIfTokenExpired(dbToken);
		
		dbToken.setName(this.getName(dbToken, principal.getRoles()));
		
		return Response.ok(dbToken).build();
	}

	@DELETE
	@RolesAllowed(Role.CLIENT)
	@Transaction
	public Response delete(@Context SecurityContext sc) {

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();

		UUID uuid = tokenTable.parseUUID(principal.getTokenKey());

		tokenTable.delete(uuid);

		return Response.noContent().build();
	}

	private String getName(Token token, HashSet<String> roles ) {
		if (roles.contains(Role.CLIENT)) {
			return this.userTable.get(new UserId(token.getUsername())).getName();
		} else {
			return null;
		}
	}

}
