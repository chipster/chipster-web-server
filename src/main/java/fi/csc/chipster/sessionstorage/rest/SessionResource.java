package fi.csc.chipster.sessionstorage.rest;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.glassfish.jersey.media.sse.EventOutput;
import org.glassfish.jersey.media.sse.SseFeature;

import fi.csc.chipster.rest.Hibernate;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessionstorage.model.Authorization;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.chipster.sessionstorage.model.SessionEvent;
import fi.csc.chipster.sessionstorage.model.SessionEvent.EventType;

@Path("sessions")
public class SessionResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(SessionResource.class.getName());
	
	// sub-resource locators
	@Path("{id}/datasets")
	public Object getDatasetResource(@PathParam("id") String id, @QueryParam("username") String username) {
		return new DatasetResource(id, username);
	}
	
	@Path("{id}/jobs")
	public Object getJobResource(@PathParam("id") String id, @QueryParam("username") String username) {
		return new JobResource(id, username);
	}
	
	// notifications
    @GET
    @Path("{id}/events")
    @Produces(SseFeature.SERVER_SENT_EVENTS)
    public EventOutput listenToBroadcast(@PathParam("id") String id, @QueryParam("username") String username) {
		Hibernate.beginTransaction();
		checkReadAuthorization(username, id);
		Hibernate.commit();
        return Events.getEventOutput();
    }
	
    // CRUD
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") String id, @QueryParam("username") String username) throws IOException {
    	    
		Hibernate.beginTransaction();
		checkReadAuthorization(username, id);
    	Session result = (Session) Hibernate.session().get(Session.class, id);    	
    	Hibernate.commit();
    	
    	if (result == null) {
    		throw new NotFoundException();
    	}	
    	return Response.ok(result).build();    	
    }
        
	@GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAll(@QueryParam("username") String username) {

		Hibernate.beginTransaction();
		@SuppressWarnings("unchecked")
		List<Authorization> result = Hibernate.session()
			.createQuery("from Authorization where username=:username")
			.setParameter("username", username)
			.list();
		
		List<Session> sessions = new ArrayList<>();
		for (Authorization auth : result) {
			sessions.add(auth.getSession());
		}
		
		//List<Session> result = Hibernate.session().createQuery("from Session").list();
		Hibernate.commit();

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(sessions)).build();
		//return Response.ok(toJaxbList(result)).build();
    }	

	@POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response post(Session session, @Context UriInfo uriInfo, @QueryParam("username") String username) {
		
		session = RestUtils.getRandomSession();
		session.setSessionId(null);
    	        	
		if (session.getSessionId() != null) {
			throw new BadRequestException("session already has an id, post not allowed");
		}

		session.setSessionId(RestUtils.createId());
		
		//FIXME null username
		Authorization auth = new Authorization(username, session);

		Hibernate.beginTransaction();
		Hibernate.session().save(auth);
		//Hibernate.session().save(session);
		Hibernate.commit();

		URI uri = uriInfo.getAbsolutePathBuilder().path(session.getSessionId()).build();
		Events.broadcast(new SessionEvent(session.getSessionId(), EventType.CREATE));
		return Response.created(uri).build();
    }

	@PUT
	@Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response put(Session session, @PathParam("id") String id, @QueryParam("username") String username) {
				    		
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		session.setSessionId(id);
		Hibernate.beginTransaction();
		if (Hibernate.session().get(Session.class, id) == null) {
			// transaction will commit, but we haven't changed anything
			return Response.status(Status.NOT_FOUND)
					.entity("session doesn't exist").build();
		}
		checkWriteAuthorization(username, id);
		Hibernate.session().merge(session);
		Hibernate.commit();

		// more fine-grained events are needed, like "job added" and "dataset removed"
		Events.broadcast(new SessionEvent(id, EventType.UPDATE));
		return Response.noContent().build();
    }

	@DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id, @QueryParam("username") String username) {

		Hibernate.beginTransaction();
		Authorization auth = checkWriteAuthorization(username, id);
		// this will delete also the referenced datasets and jobs
		Hibernate.session().delete(auth);
		//Hibernate.session().delete(Hibernate.session().load(Session.class, id));
		Hibernate.commit();

		Events.broadcast(new SessionEvent(id, EventType.DELETE));
		return Response.noContent().build();
    }
	
	public static Authorization checkReadAuthorization(String username, String sessionId) {
    	return checkAuthorization(username, sessionId, false);
    }
	public static Authorization checkWriteAuthorization(String username, String sessionId) {
    	return checkAuthorization(username, sessionId, true);
    }
    
	private static Authorization checkAuthorization(String username, String sessionId, boolean requireReadWrite) {

		if(username == null) {
			throw new NotAuthorizedException(Response.noContent());
		}
		Session session = (Session) Hibernate.session().get(Session.class, sessionId);
		Object authObj = Hibernate.session().createQuery(
				"from Authorization"
				+ " where username=:username and session=:session")
				.setParameter("username", username)
				.setParameter("session", session).uniqueResult();
		if (authObj == null) {
			throw new ForbiddenException();
		}
		Authorization auth = (Authorization)authObj;
		if (requireReadWrite) {
			if (!auth.isReadWrite()) {
				throw new ForbiddenException();
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
}
