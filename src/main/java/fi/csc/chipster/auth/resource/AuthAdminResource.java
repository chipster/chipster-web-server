package fi.csc.chipster.auth.resource;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.JerseyStatisticsSource;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotFoundRollbackException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

public class AuthAdminResource extends AdminResource {

	private static Logger logger = LogManager.getLogger();
	public static final String PATH_USERS = "users";

	private HibernateUtil hibernate;
	private UserTable userTable;

	
	
	public AuthAdminResource(HibernateUtil hibernate, 
			List<Class<?>> classes, JerseyStatisticsSource jerseyStats, UserTable userTable) {
		super(hibernate, classes, jerseyStats);
		this.userTable = userTable;
		this.hibernate = hibernate;
	}


	
	/**
	 * Could also return simple string, but returning json, since most other api methods return json.
	 * 
	 * @param userId
	 * @param sc
	 * @return
	 */
	@DELETE
	@Path(PATH_USERS)
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response deleteUser(@NotNull @QueryParam("userId") List<String> userId, @Context SecurityContext sc) {
		logger.info("deleting user " + userId);
		
		List<String> deletedUserIds = new ArrayList<String>();
		for (String idString: userId) {
			UserId id = new UserId(idString);
			User user = userTable.get(id, hibernate.session());
			if (user == null) {
				throw new NotFoundRollbackException(RestUtils.asJson(new String[] {idString}));
//				return Response.status(404, "User " + idString + " not found").entity(deletedUserIds).build();
			}
			
			HibernateUtil.delete(user, id, hibernate.session());
			deletedUserIds.add(idString);
		}
		
		return Response.ok(deletedUserIds).build();
	}
	
}
