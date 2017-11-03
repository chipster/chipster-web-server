package fi.csc.chipster.sessiondb.resource;

import java.net.URI;
import java.util.Collection;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;

@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
public class SessionJobResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	final private UUID sessionId;

	private SessionResource sessionResource;
	
	public SessionJobResource() {
		sessionId = null;
	}
	
	public SessionJobResource(SessionResource sessionResource, UUID id) {
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
    	Session session = sessionResource.getRuleTable().getSessionForReading(sc, sessionId);
    	Job result = getJob(jobId, getHibernate().session());
    	
    	if (result == null || result.getSession().getSessionId() != session.getSessionId()) {
    		throw new NotFoundException();
    	}
    	
   		return Response.ok(result).build();
    }
    
    public Job getJob(UUID jobId, org.hibernate.Session hibernateSession) {
    	
    	return hibernateSession.get(Job.class, jobId);    	
    }
    
	@GET
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAll(@Context SecurityContext sc) {
		
		Collection<Job> result = sessionResource.getRuleTable().getSessionForReading(sc, sessionId).getJobs().values();

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(result)).build();
    }	

	@POST
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response post(Job job, @Context UriInfo uriInfo, @Context SecurityContext sc) {		        	
		
		if (job.getJobId() != null) {
			throw new BadRequestException("job already has an id, post not allowed");
		}
		
		UUID id = RestUtils.createUUID();
		job.setJobId(id);
		
		Session session = sessionResource.getRuleTable().getSessionForWriting(sc, sessionId);
		// make sure a hostile client doesn't set the session
		job.setSession(session);
		
		// let the db generate ids
		for (Input input : job.getInputs()) {
			input.setDbId(0);
		}
		
		for (Parameter param : job.getParameters()) {
			param.setDbId(0);
		}

		create(job, getHibernate().session());
		
		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();
		
		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("jobId", id.toString());		
		
		return Response.created(uri).entity(json).build();
    }

	public void create(Job job, org.hibernate.Session hibernateSession) {
		hibernateSession.save(job);
		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.JOB, job.getJobId(), EventType.CREATE), hibernateSession);
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
		Session session = sessionResource.getRuleTable().getSessionForWriting(sc, sessionId);
		Job dbJob = getHibernate().session().get(Job.class, jobId);
		if (dbJob == null || dbJob.getSession().getSessionId() != session.getSessionId()) {
			throw new NotFoundException("job doesn't exist");
		}
		// make sure a hostile client doesn't set the session
		requestJob.setSession(session);
		
		// let the db generate ids
		for (Input input : requestJob.getInputs()) {
			input.setDbId(0);
		}
		
		for (Parameter param : requestJob.getParameters()) {
			param.setDbId(0);
		}
		
		update(requestJob, getHibernate().session());
		
		return Response.noContent().build();
    }
	
	public void update(Job job, org.hibernate.Session hibernateSession) {
		hibernateSession.merge(job);
		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.JOB, job.getJobId(), EventType.UPDATE), hibernateSession);
	}

	@DELETE
    @Path("{id}")
	@Transaction
    public Response delete(@PathParam("id") UUID jobId, @Context SecurityContext sc) {

		// checks authorization
		Session session = sessionResource.getRuleTable().getSessionForWriting(sc, sessionId);
		Job dbJob = getHibernate().session().get(Job.class, jobId);
		
		if (dbJob == null || dbJob.getSession().getSessionId() != session.getSessionId()) {
			throw new NotFoundException("job not found");
		}
		
		deleteJob(dbJob, getHibernate().session());
		
		return Response.noContent().build();
    }
	
	public void deleteJob(Job job, org.hibernate.Session hibernateSession) {
		hibernateSession.delete(job);		
		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.JOB, job.getJobId(), EventType.DELETE), hibernateSession);
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
