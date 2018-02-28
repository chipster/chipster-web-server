package fi.csc.chipster.auth.resource;

import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import fi.csc.chipster.auth.UserTable;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path(SsoTokenResource.SSO)
@RolesAllowed(Role.SSO)
public class SsoTokenResource {

	public static final String SSO = "sso";
		
	private TokenTable tokenTable;

	private HashSet<String> specialUsernames;
	private UserTable userTable;

	public SsoTokenResource(Config config, TokenTable tokenTable, UserTable userTable) {
				
		this.tokenTable = tokenTable;
		this.userTable = userTable;
		
		this.specialUsernames = new HashSet<String>(); 
		this.specialUsernames.addAll(config.getAdminAccounts());
		this.specialUsernames.addAll(config.getServicePasswords().keySet());
	}	
	
	@POST
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response createToken(User user, @Context SecurityContext sc) {

		String username = user.getUserId().getUsername();
		
		if (username == null) {
			throw new BadRequestException("username is null");
		}
		
		if (specialUsernames.contains(username)) {
			throw new BadRequestException("admin and service accounts cannot login with sso");
		}
		
		// authenticator is part of the userId
		user.getUserId().setAuth(sc.getUserPrincipal().getName());
		
		String userIdString = user.getUserId().toUserIdString();
		
		userTable.addOrUpdate(user);
		
		HashSet<String> roles = Stream.of(Role.CLIENT, Role.SSO).collect(Collectors.toCollection(HashSet::new));

		// shibboleth can login anyone
		Token token = tokenTable.createAndSaveToken(userIdString, roles);

		return Response.ok(token).build();
	}

}
