package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Set;

import javax.annotation.security.RolesAllowed;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.ParsedToken;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path("tokens")
public class AuthTokenResource {

	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();
	
	public static final String TOKEN_HEADER = "chipster-token";

	public static final String PATH_TOKENS = "tokens";
	public static final String PATH_PUBLIC_KEY = "publicKey";

	private AuthTokens tokens;
	private UserTable userTable;

	public AuthTokenResource(AuthTokens tokenTable, UserTable userTable) throws URISyntaxException, IOException {
		this.tokens = tokenTable;
		this.userTable = userTable;
	}

	@POST
	@RolesAllowed(Role.PASSWORD)
	@Produces(MediaType.TEXT_PLAIN)
	@Transaction // getName() uses the db
	public Response createToken(@Context SecurityContext sc) {

		// curl -i -H "Content-Type: application/json" --user client:clientPassword -X POST http://localhost:8081/auth/tokens

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
		String username = sc.getUserPrincipal().getName();

		if (username == null) {
			// RolesAllowed prevents this
			throw new NotAuthorizedException("username is null");
		}
		
		String token = tokens.createNewToken(username, principal.getRoles(), this.getName(username, principal.getRoles()));
		
		return Response.ok(token).build();
	}
	
	/**
	 * Check the token validity on the server
	 * 
	 * Most services shouldn't use this, but get the public key and check the token themselves 
	 * for performance reasons.
	 * If that is impractical for some service, it can also use this endpoint to do the validation.
	 * 
	 * TODO find a way to pass the client's error (NotAuthorized or Forbidden), to make it
	 * easier to switch between these two ways of validation.  
	 * 
	 * @param requestToken
	 * @param sc
	 * @return
	 */
	@GET
	@RolesAllowed(Role.SERVER)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction // getName() uses the db
	public Response checkToken(@HeaderParam(TOKEN_HEADER) String requestToken, @Context SecurityContext sc) {

		ParsedToken validToken;
		try {
			validToken = tokens.validateToken(requestToken);
		} catch (NotAuthorizedException | ForbiddenException e) {
			// NotAuthorized and Forbidden are not suitable here, because the server authenticated correctly in the TokenRequestFilter			
			throw new NotFoundException(e);
		}

		return Response.ok(validToken).build();					
	}
	
	@GET
	@Path(PATH_PUBLIC_KEY)
	// this could be also unauthenticated, but client hasn't needed it so far
	@RolesAllowed(Role.SERVER)
	public Response getPublicKey(@Context SecurityContext sc) {

		return Response.ok(this.tokens.getPublicKey()).build();					
	}	

	@POST
	@Path("refresh")
	@RolesAllowed({Role.CLIENT, Role.SERVER})
	@Produces(MediaType.TEXT_PLAIN)
	@Transaction // getName() uses the db
	public Response refreshToken(@Context SecurityContext sc) {

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
		String oldToken = principal.getTokenKey();

		String newToken = tokens.refreshToken(oldToken, this.getName(principal.getName(), principal.getRoles()));
		
		return Response.ok(newToken).build();
	}

	@GET
	@Path("check")
	@RolesAllowed({Role.CLIENT, Role.SERVER})
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction // getName() uses the db
	public Response checkClientToken(@Context SecurityContext sc) {

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
		String token = principal.getTokenKey();

		// throws if fails
		ParsedToken validToken = tokens.validateToken(token);
		
		return Response.ok(validToken).build();
	}

	private String getName(String username, Set<String> roles ) {
		// service accounts are not in the userTable
		if (roles.contains(Role.CLIENT)) {
			return this.userTable.get(new UserId(username)).getName();
		} else {
			return null;
		}
	}

}
