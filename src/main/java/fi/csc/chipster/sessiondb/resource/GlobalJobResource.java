package fi.csc.chipster.sessiondb.resource;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.scheduler.IdPair;
import fi.csc.chipster.sessiondb.model.Job;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.SecurityContext;

@Path("jobs")
public class GlobalJobResource {

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;

	public GlobalJobResource(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}

	@GET
	@RolesAllowed({ Role.SCHEDULER, Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getAll(@QueryParam("state") String stateString, @Context SecurityContext sc) {

		if (stateString == null) {
			return Response.status(Status.BAD_REQUEST).entity("query parameter 'state' is mandatory").build();
		}

		try {
			// throws if invalid
			JobState state = JobState.valueOf(stateString);

			List<Job> jobs = hibernate.session()
					.createQuery("from Job where state=:state", Job.class)
					.setParameter("state", state)
					.list();

			// Convert to IdPairs, because the Job JSON doesn't include the sessionId
			// update: it does now, but is thisidPair list still a good concise format?
			List<IdPair> idPairs = jobs.stream().map(job -> new IdPair(job.getSessionId(), job.getJobId()))
					.collect(Collectors.toList());

			return Response.ok(idPairs).build();

		} catch (IllegalArgumentException e) {
			return Response.status(Status.BAD_REQUEST).entity("invalid state").build();
		}
	}
}
