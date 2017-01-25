package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.hibernate.BaseSessionEventListener;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.sessiondb.SessionDb;
import fi.csc.chipster.sessiondb.model.Authorization;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
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

	private AuthorizationResource authorizationResource;
	
	public SessionResource(HibernateUtil hibernate, AuthorizationResource authorizationResource) {
		this.hibernate = hibernate;
		this.authorizationResource = authorizationResource;
	}

	// sub-resource locators
	@Path("{id}/datasets")
	public DatasetResource getDatasetResource(@PathParam("id") UUID id) {
		return new DatasetResource(this, id);
	}
	
	@Path("{id}/jobs")
	public JobResource getJobResource(@PathParam("id") UUID id) {
		return new JobResource(this, id);
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
    	
    	result.setAccessed(LocalDateTime.now());
    	
    	return Response.ok(result).build();    	
    }
    
	@GET
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAll(@Context SecurityContext sc) {

		List<Authorization> result = authorizationResource.getAuthorizations(sc.getUserPrincipal().getName());
		
		List<Session> sessions = new ArrayList<>();
		for (Authorization auth : result) {
			sessions.add(auth.getSession());
		}		

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(sessions)).build();
    }
	

	@POST
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
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
		session.setCreated(LocalDateTime.now());
		session.setAccessed(LocalDateTime.now());
		
		String username = sc.getUserPrincipal().getName();
		if (username == null) {
			throw new NotAuthorizedException("username is null");
		}
		Authorization auth = new Authorization(username, session, true);
		auth.setAuthorizationId(RestUtils.createUUID());
		
		create(auth, getHibernate().session());
		
		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();
		
		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("sessionId", id.toString());
		
		return Response.created(uri).entity(json).build();
    }
	
	public void create(Authorization auth, org.hibernate.Session hibernateSession) {
		authorizationResource.save(auth, hibernateSession);

		UUID sessionId = auth.getSession().getSessionId();
		publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.SESSION, sessionId, EventType.CREATE), hibernateSession);
		publish(SessionDb.AUTHORIZATIONS_TOPIC, new SessionEvent(sessionId, ResourceType.AUTHORIZATION, auth.getAuthorizationId(), EventType.CREATE), hibernateSession);
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
		
		requestSession.setAccessed(LocalDateTime.now());
		
		update(requestSession, getHibernate().session());
		
		return Response.noContent().build();
    }
	
	public void update(Session session, org.hibernate.Session hibernateSession) {
		UUID sessionId = session.getSessionId();
		// persist
		hibernateSession.merge(session);
		publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.SESSION, sessionId, EventType.UPDATE), hibernateSession);
	}

	@DELETE
    @Path("{id}")
	@Transaction
    public Response delete(@PathParam("id") UUID id, @Context SecurityContext sc) {

		Authorization auth = getWriteAuthorization(sc, id);
				
		deleteSession(auth, getHibernate().session());

		return Response.noContent().build();
    }
	
	public void deleteSession(Authorization auth, org.hibernate.Session hibernateSession) {
		
		UUID sessionId = auth.getSession().getSessionId();
		
		/*
		 * All datasets have to be removed first, because the dataset owns the reference between 
		 * the dataset and the session. This also generates the necessary events e.g. to remove
		 * files.  
		 */
		for (Dataset dataset : auth.getSession().getDatasets().values()) {
			getDatasetResource(sessionId).deleteDataset(dataset, hibernateSession);
		}
		
		// see the note about datasets above
		for (Job job : auth.getSession().getJobs().values()) {
			getJobResource(sessionId).deleteJob(job, hibernateSession);
		}

		hibernateSession.delete(auth);

		publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.AUTHORIZATION, sessionId, EventType.DELETE), hibernateSession);
		publish(SessionDb.AUTHORIZATIONS_TOPIC, new SessionEvent(sessionId, ResourceType.AUTHORIZATION, auth.getAuthorizationId(), EventType.DELETE), hibernateSession);
	}
	
	public Session getSessionForReading(SecurityContext sc, UUID sessionId) {
		Authorization auth = authorizationResource.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, false);
		return auth.getSession();
	}
	
	public Session getSessionForWriting(SecurityContext sc, UUID sessionId) {
		Authorization auth = authorizationResource.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, true);
		return auth.getSession();
	}
	
	public Authorization getReadAuthorization(SecurityContext sc, UUID sessionId) {
    	return authorizationResource.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, false);
    }
	public Authorization getWriteAuthorization(SecurityContext sc, UUID sessionId) {
    	return authorizationResource.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, true);
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

	public void setPubSubServer(PubSubServer pubSubServer) {
		this.events = pubSubServer;
	}
	
	public void publish(final String topic, final SessionEvent obj, org.hibernate.Session hibernateSession) {
		// publish the event only after the transaction is completed to make 
		// sure that the modifications are visible
		hibernateSession.addEventListeners(new BaseSessionEventListener() {
			@Override
			public void transactionCompletion(boolean successful) {
				events.publish(topic, obj);
				if (ResourceType.JOB == obj.getResourceType()) {
					events.publish(SessionDb.JOBS_TOPIC, obj);
				}
				if (ResourceType.SESSION == obj.getResourceType()) {
					events.publish(SessionDb.SESSIONS_TOPIC, obj);
				}
				if (ResourceType.DATASET == obj.getResourceType()) {
					events.publish(SessionDb.DATASETS_TOPIC, obj);
				}
				// authorization events are sent directly to AUTHORIZATIONS_TOPIC, because
				// client's don't need them
			}				
		});	
	}

	public AuthorizationResource getAuthorizationResource() {
		return authorizationResource;
	}
}
