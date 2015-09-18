package fi.csc.chipster.sessionstorage.resource;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.logging.Logger;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessionstorage.model.Job;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.chipster.sessionstorage.model.SessionEvent;
import fi.csc.chipster.sessionstorage.model.SessionEvent.EventType;
import fi.csc.chipster.sessionstorage.model.SessionEvent.ResourceType;

public class JobResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(JobResource.class.getName());
	
	final private String sessionId;

	private SessionResource sessionResource;

	private Events events;
	
	public JobResource() {
		sessionId = null;
	}
	
	public JobResource(SessionResource sessionResource, String id, Events events) {
		this.sessionResource = sessionResource;
		this.sessionId = id;
		this.events = events;
	}
	
    // CRUD
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response get(@PathParam("id") String jobId, @Context SecurityContext sc) {

    	// checks authorization
    	Session session = sessionResource.getSessionForReading(sc, sessionId);
    	Job result = session.getJobs().get(jobId);
    	
    	if (result == null) {
    		throw new NotFoundException();
    	}

   		return Response.ok(result).build();
    }
    
	@GET
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAll(@Context SecurityContext sc) {
		
		Collection<Job> result = sessionResource.getSessionForReading(sc, sessionId).getJobs().values();

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(result)).build();
    }	

	@POST
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response post(Job job, @Context UriInfo uriInfo, @Context SecurityContext sc) {		        	
		
		if (job.getJobId() != null) {
			throw new BadRequestException("job already has an id, post not allowed");
		}
		
		String id = RestUtils.createId();
		job.setJobId(id);
		
		sessionResource.getSessionForWriting(sc, sessionId).getJobs().put(id, job);

		URI uri = uriInfo.getAbsolutePathBuilder().path(id).build();
		events.broadcast(new SessionEvent(sessionId, ResourceType.JOB, id, EventType.CREATE));
		return Response.created(uri).build();
    }

	@PUT
	@Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response put(Job requestJob, @PathParam("id") String jobId, @Context SecurityContext sc) {
				    		
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		requestJob.setJobId(jobId);

		/*
		 * Checks that
		 * - user has write authorization for the session
		 * - the session contains this dataset
		 */
		if (!sessionResource.getSessionForWriting(sc, sessionId).getJobs().containsKey(jobId)) {
			throw new NotFoundException("job doesn't exist");
		}
		getHibernate().session().merge(requestJob);

		// more fine-grained events are needed, like "job added" and "job removed"
		events.broadcast(new SessionEvent(sessionId, ResourceType.JOB, jobId, EventType.UPDATE));
		return Response.noContent().build();
    }

	@DELETE
    @Path("{id}")
	@Transaction
    public Response delete(@PathParam("id") String jobId, @Context SecurityContext sc) {

		// checks authorization
		Map<String, Job> jobs = sessionResource.getSessionForWriting(sc, sessionId).getJobs();
		
		if (!jobs.containsKey(jobId)) {
			throw new NotFoundException("job not found");
		}
		
		// remove from session, hibernate will take care of the actual dataset table
		jobs.remove(jobId);

		events.broadcast(new SessionEvent(sessionId, ResourceType.JOB, jobId, EventType.DELETE));
		return Response.noContent().build();
    }

    /**
	 * Make a list compatible with JSON conversion
	 * 
	 * Default Java collections don't define the XmlRootElement annotation and 
	 * thus can't be converted directly to JSON. 
	 * @param <T>
	 * 
	 * @param result
	 * @return
	 */
	private GenericEntity<Collection<Job>> toJaxbList(Collection<Job> result) {
		return new GenericEntity<Collection<Job>>(result) {};
	}
	
	private HibernateUtil getHibernate() {
		return sessionResource.getHibernate();
	}
}
