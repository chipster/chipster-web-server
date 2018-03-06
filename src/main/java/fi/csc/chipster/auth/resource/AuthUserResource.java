package fi.csc.chipster.auth.resource;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path(AuthUserResource.USERS)
public class AuthUserResource {

	public static final String USERS = "users";
	private UserTable userTable;

	public AuthUserResource(UserTable userTable) {
		this.userTable = userTable;
	}
	
	@GET
	@Path("{userId}")
	@RolesAllowed(Role.CLIENT)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response get(@PathParam("userId") String userId, @Context SecurityContext sc) {
		
		if (sc.getUserPrincipal().getName().equals(userId)) {
			return Response.ok(userTable.get(new UserId(userId))).build();
		}
		throw new ForbiddenException();			
	}
	
	@GET
	@RolesAllowed(Role.ADMIN)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response get() {
		
		return Response.ok(userTable.getAll()).build();			
	}
}
