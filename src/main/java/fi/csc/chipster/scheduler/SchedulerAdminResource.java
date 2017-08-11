
package fi.csc.chipster.scheduler;

import java.util.HashMap;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.GenericAdminResource;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path("admin")
public class SchedulerAdminResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private Scheduler scheduler;

	public SchedulerAdminResource(Scheduler scheduler) {
		this.scheduler = scheduler;
	}

	@GET
	@Path(GenericAdminResource.PATH_STATUS)
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getStatus(@Context SecurityContext sc) {
		
		HashMap<String, Object> status = new HashMap<>();
		
		if (sc.isUserInRole(Role.ADMIN)) {
			
			status.putAll(scheduler.getStatus());			
			status.putAll(GenericAdminResource.getSystemStats());
		}
		
		status.put(GenericAdminResource.KEY_STATUS, GenericAdminResource.VALUE_OK);
	
		return Response.ok(status).build();
		
    }	
}
