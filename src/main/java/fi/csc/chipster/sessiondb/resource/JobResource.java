package fi.csc.chipster.sessiondb.resource;

import java.net.URI;
import java.util.Collection;
import java.util.UUID;

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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;

public class JobResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	final private UUID sessionId;

	private SessionResource sessionResource;
	
	public JobResource() {
		sessionId = null;
	}
	
	public JobResource(SessionResource sessionResource, UUID id) {
		this.sessionResource = sessionResource;
		this.sessionId = id;
	}
	
    // CRUD
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response get(@PathParam("id") UUID jobId, @Context SecurityContext sc) {
    	
    	// checks authorization
    	Session session = sessionResource.getSessionForReading(sc, sessionId);
    	Job result = getHibernate().session().get(Job.class, jobId);
    	
    	if (result == null || result.getSession().getSessionId() != session.getSessionId()) {
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
		
		UUID id = RestUtils.createUUID();
		job.setJobId(id);
		
		Session session = sessionResource.getSessionForWriting(sc, sessionId);
		// make sure a hostile client doesn't set the session
		job.setSession(session);
		getHibernate().session().save(job);

		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();
		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.JOB, id, EventType.CREATE));
		
		return Response.created(uri).build();
    }

	@PUT
	@Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response put(Job requestJob, @PathParam("id") UUID jobId, @Context SecurityContext sc) {
				    		
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		requestJob.setJobId(jobId);
		
		/*
		 * Checks that
		 * - user has write authorization for the session
		 * - the session contains this dataset
		 */
		Session session = sessionResource.getSessionForWriting(sc, sessionId);
		Job dbJob = getHibernate().session().get(Job.class, jobId);
		if (dbJob == null || dbJob.getSession().getSessionId() != session.getSessionId()) {
			throw new NotFoundException("job doesn't exist");
		}
		// make sure a hostile client doesn't set the session
		requestJob.setSession(session);
		getHibernate().session().merge(requestJob);

		// more fine-grained events are needed, like "job added" and "job removed"
		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.JOB, jobId, EventType.UPDATE));
		return Response.noContent().build();
    }

	@DELETE
    @Path("{id}")
	@Transaction
    public Response delete(@PathParam("id") UUID jobId, @Context SecurityContext sc) {

		// checks authorization
		Session session = sessionResource.getSessionForWriting(sc, sessionId);
		Job dbJob = getHibernate().session().get(Job.class, jobId);
		
		if (dbJob == null || dbJob.getSession().getSessionId() != session.getSessionId()) {
			throw new NotFoundException("job not found");
		}
		
		getHibernate().session().delete(dbJob);

		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.JOB, jobId, EventType.DELETE));
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
