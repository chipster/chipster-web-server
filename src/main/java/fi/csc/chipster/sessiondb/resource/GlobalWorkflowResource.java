package fi.csc.chipster.sessiondb.resource;

import java.util.List;
import java.util.stream.Collectors;

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
import fi.csc.chipster.scheduler.IdPair;
import fi.csc.chipster.sessiondb.model.WorkflowRun;
import fi.csc.microarray.messaging.JobState;

@Path("workflows")
public class GlobalWorkflowResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;
	
	public GlobalWorkflowResource(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}
    
	@GET
	@Path("runs")
	@RolesAllowed({Role.SCHEDULER, Role.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAllRuns(@QueryParam("state") String stateString, @Context SecurityContext sc) {

		if (stateString == null) {
			return Response.status(Status.BAD_REQUEST).entity("query parameter 'state' is mandatory").build();
		}
				
		try {		
			// throws if invalid
			JobState state = JobState.valueOf(stateString);
			
			@SuppressWarnings("unchecked")
			List<WorkflowRun> runs = hibernate.session()
					.createQuery("from WorkflowRun where state=:state")
					.setParameter("state", state)
					.list();
			
			// Convert to IdPairs, because the Job JSON doesn't include the sessionId
			// update: it does now, but is thisidPair list still a good concise format?
			List<IdPair> idPairs = runs.stream().map(run -> new IdPair(run.getSessionId(), run.getWorkflowRunId()))
					.collect(Collectors.toList());
			
			return Response.ok(idPairs).build();

		} catch (IllegalArgumentException e) {
			return Response.status(Status.BAD_REQUEST).entity("invalid state").build();			
		}			
    }	
}
