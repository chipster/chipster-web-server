package fi.csc.chipster.auth.resource;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.hibernate.Transaction;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

@Path(AuthUserResource.USERS)
public class AuthUserResource {

	public static final String USERS = "users";
	public static final String USER_ID_KEY = "userId";
	private UserTable userTable;

	public AuthUserResource(UserTable userTable) {
		this.userTable = userTable;
	}
	
	@GET
	@RolesAllowed({Role.CLIENT, Role.SESSION_WORKER, Role.ADMIN})
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response get(@QueryParam(USER_ID_KEY) String userId, @Context SecurityContext sc) {
	    
	    AuthPrincipal authPrincipal = (AuthPrincipal)sc.getUserPrincipal();
        
        if (authPrincipal.getRoles().contains(Role.ADMIN) && userId == null) {
            
            return Response.ok(userTable.getAll()).build();            
        } 
	    
		String authenticatedUserId = sc.getUserPrincipal().getName();
		if (authenticatedUserId.equals(userId) // client cat get it's own User object 
				|| authenticatedUserId.equals(Role.SESSION_WORKER)) { // session-worker can get all
			
			return Response.ok(userTable.get(new UserId(userId))).build();
		}
		
		throw new ForbiddenException();			
	}
	
	@PUT
	@RolesAllowed(Role.CLIENT)	
	@Consumes(MediaType.APPLICATION_JSON)
	@Transaction
	public Response put(User user, @QueryParam(USER_ID_KEY) String userId, @Context SecurityContext sc) {
		
		if (sc.getUserPrincipal().getName().equals(userId)) {
			// don't allow malicious client to update other objects
			user.setUserId(new UserId(userId));
			userTable.update(user);
			return Response.noContent().build();
		}
		throw new ForbiddenException();			
	}
}
