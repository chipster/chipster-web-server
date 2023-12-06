package fi.csc.chipster.sessiondb.resource;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

@Path(UserResource.PATH_USERS)
public class UserResource {

	public static final String PATH_USERS = "users";

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;
	private RuleTable ruleTable;

	public UserResource(HibernateUtil hibernate, RuleTable ruleTable) {
		this.hibernate = hibernate;
		this.ruleTable = ruleTable;
	}

	@GET
	@RolesAllowed({ Role.ADMIN, Role.FILE_STORAGE, Role.FILE_BROKER })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getAll(@Context SecurityContext sc) {
		List<String> users = ruleTable.getUsers();
		return Response.ok(users).build();
	}

}
