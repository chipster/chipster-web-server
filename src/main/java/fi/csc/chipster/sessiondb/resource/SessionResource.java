package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.ForbiddenException;
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

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.scheduler.PubSubServer;
import fi.csc.chipster.sessiondb.model.Authorization;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;

@Path("sessions")
public class SessionResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;
	private PubSubServer events;
	
	public SessionResource(HibernateUtil hibernate, PubSubServer pubSubServer) {
		this.hibernate = hibernate;
		this.events = pubSubServer;
	}

	// sub-resource locators
	@Path("{id}/datasets")
	public Object getDatasetResource(@PathParam("id") UUID id) {
		return new DatasetResource(this, id, events);
	}
	
	@Path("{id}/jobs")
	public Object getJobResource(@PathParam("id") UUID id) {
		return new JobResource(this, id, events);
	}
	
    // CRUD
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response get(@PathParam("id") UUID sessionId, @Context SecurityContext sc) throws IOException {
    	    
    	// checks authorization
    	Session result = getSessionForReading(sc, sessionId);    	
    	
    	if (result == null) {
    		throw new NotFoundException();
    	}	
    	return Response.ok(result).build();    	
    }
        
	@GET
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAll(@Context SecurityContext sc) {

		@SuppressWarnings("unchecked")
		List<Authorization> result = getHibernate().session()
			.createQuery("from Authorization where username=:username")
			.setParameter("username", sc.getUserPrincipal().getName())
			.list();
		
		List<Session> sessions = new ArrayList<>();
		for (Authorization auth : result) {
			sessions.add(auth.getSession());
		}		

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(sessions)).build();
    }	

	@POST
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response post(Session session, @Context UriInfo uriInfo, @Context SecurityContext sc) {
	//public Response post(String json, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		
		// curl -i -H "Content-Type: application/json" --user token:<TOKEN> -X POST http://localhost:8080/sessionstorage/session
//		System.out.println("POST JSON: " + json);
//		Session session = RestUtils.parseJson(Session.class, json);				
//		
		if (session.getSessionId() != null) {
			throw new BadRequestException("session already has an id, post not allowed");
		}

		UUID id = RestUtils.createUUID();
		session.setSessionId(id);
		
		String username = sc.getUserPrincipal().getName();
		if (username == null) {
			throw new NotAuthorizedException("username is null");
		}
		Authorization auth = new Authorization(username, session, true);

		getHibernate().session().save(auth);

		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();
		events.publish(id.toString(), new SessionEvent(id, ResourceType.SESSION, id, EventType.CREATE));
		
		return Response.created(uri).build();
    }

	@PUT
	@Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response put(Session requestSession, @PathParam("id") UUID sessionId, @Context SecurityContext sc) {
				    				
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		requestSession.setSessionId(sessionId);
		
		// checks the authorization and verifies that the session exists
		getWriteAuthorization(sc, sessionId);
		Session dbSession = getHibernate().session().get(Session.class, requestSession.getSessionId());
		// keep the old datasets and jobs
		requestSession.setDatasets(dbSession.getDatasets());
		requestSession.setJobs(dbSession.getJobs());
		// persist
		getHibernate().session().merge(requestSession);

		// more fine-grained events are needed, like "job added" and "dataset removed"
		events.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.SESSION, sessionId, EventType.UPDATE));
		return Response.noContent().build();
    }

	@DELETE
    @Path("{id}")
	@Transaction
    public Response delete(@PathParam("id") UUID id, @Context SecurityContext sc) {

		Authorization auth = getWriteAuthorization(sc, id);
		// this will delete also the referenced datasets and jobs
		getHibernate().session().delete(auth);

		events.publish(id.toString(), new SessionEvent(id, ResourceType.SESSION, id, EventType.DELETE));
		return Response.noContent().build();
    }
	
	public Session getSessionForReading(SecurityContext sc, UUID sessionId) {
		Authorization auth = checkAuthorization(sc, sessionId, false);
		return auth.getSession();
	}
	
	public Session getSessionForWriting(SecurityContext sc, UUID sessionId) {
		Authorization auth = checkAuthorization(sc, sessionId, true);
		return auth.getSession();
	}
	
	public Authorization getReadAuthorization(SecurityContext sc, UUID sessionId) {
    	return checkAuthorization(sc, sessionId, false);
    }
	public Authorization getWriteAuthorization(SecurityContext sc, UUID sessionId) {
    	return checkAuthorization(sc, sessionId, true);
    }
    
	private Authorization checkAuthorization(SecurityContext sc, UUID sessionId, boolean requireReadWrite) {

		String username = sc.getUserPrincipal().getName();
		if(username == null) {
			throw new NotAuthorizedException("not authorized for null username");
		}
		Session session = (Session) getHibernate().session().get(Session.class, sessionId);	
		
		if (sc.isUserInRole(Role.SCHEDULER) || sc.isUserInRole(Role.COMP)) {		
			return new Authorization(username, session, true);
		}

		Object authObj = getHibernate().session().createQuery(
				"from Authorization"
				+ " where username=:username and session=:session")
				.setParameter("username", username)
				.setParameter("session", session).uniqueResult();
		if (authObj == null) {
			throw new NotFoundException("session doesn't exist or you are not authorized to access it");
		}
		Authorization auth = (Authorization)authObj;
		if (requireReadWrite) {
			if (!auth.isReadWrite()) {
				throw new ForbiddenException("read-write access denied");
			}
		}
		return auth;
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
	private GenericEntity<List<Session>> toJaxbList(List<Session> result) {
		return new GenericEntity<List<Session>>(result) {};
	}
	
	public HibernateUtil getHibernate() {
		return hibernate;
	}
}
