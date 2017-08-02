package fi.csc.chipster.sessiondb.resource;

import java.util.List;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.microarray.messaging.JobState;

@Path("jobs")
public class GlobalJobResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;
	
	public GlobalJobResource(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}
    
	@GET
	@RolesAllowed(Role.SCHEDULER)
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAll(@QueryParam("state") String stateString, @Context SecurityContext sc) {
		
		if (stateString == null) {
			return Response.status(Status.BAD_REQUEST).entity("query parameter 'state' is mandatory").build();
		}
				
		try {		
			// throws if invalid
			JobState state = JobState.valueOf(stateString);
			
			@SuppressWarnings("unchecked")
			List<Job> jobs = hibernate.session()
					.createQuery("from Job where state=:state")
					.setParameter("state", state)
					.list();
			
			return Response.ok(jobs).build();

		} catch (IllegalArgumentException e) {
			return Response.status(Status.BAD_REQUEST).entity("invalid state").build();			
		}			
    }	
}
