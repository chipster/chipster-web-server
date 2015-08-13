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
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.glassfish.jersey.media.sse.EventOutput;
import org.glassfish.jersey.media.sse.SseFeature;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.Hibernate;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessionstorage.model.Authorization;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.chipster.sessionstorage.model.SessionEvent;
import fi.csc.chipster.sessionstorage.model.SessionEvent.EventType;

@Path("sessions")
public class SessionResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(SessionResource.class.getName());
	private Hibernate hibernate;
	
	public SessionResource(Hibernate hibernate) {
		this.hibernate = hibernate;
	}

	// sub-resource locators
	@Path("{id}/datasets")
	public Object getDatasetResource(@PathParam("id") String id) {
		return new DatasetResource(this, id);
	}
	
	@Path("{id}/jobs")
	public Object getJobResource(@PathParam("id") String id) {
		return new JobResource(this, id);
	}
	
	// notifications
    @GET
    @Path("{id}/events")
    @Produces(SseFeature.SERVER_SENT_EVENTS)
    @Transaction
    public EventOutput listenToBroadcast(@PathParam("id") String id, @Context SecurityContext sc) {

		checkReadAuthorization(sc.getUserPrincipal().getName(), id);
        return Events.getEventOutput();
    }
	
    // CRUD
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response get(@PathParam("id") String id, @Context SecurityContext sc) throws IOException {
    	    
		checkReadAuthorization(sc.getUserPrincipal().getName(), id);
    	Session result = (Session) getHibernate().session().get(Session.class, id);    	
    	
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
//		long t = System.currentTimeMillis();
		
		session = RestUtils.getRandomSession();
		session.setSessionId(null);
    	        	
		if (session.getSessionId() != null) {
			throw new BadRequestException("session already has an id, post not allowed");
		}

		session.setSessionId(RestUtils.createId());
		
		//FIXME null username
		Authorization auth = new Authorization(sc.getUserPrincipal().getName(), session, true);

		getHibernate().session().save(auth);

		URI uri = uriInfo.getAbsolutePathBuilder().path(session.getSessionId()).build();
		Events.broadcast(new SessionEvent(session.getSessionId(), EventType.CREATE));
		
//		System.out.println("post session " + (System.currentTimeMillis() - t) + " ms");
		return Response.created(uri).build();
    }

	@PUT
	@Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response put(Session session, @PathParam("id") String id, @Context SecurityContext sc) {
				    		
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		session.setSessionId(id);

		if (getHibernate().session().get(Session.class, id) == null) {
			// transaction will commit, but we haven't changed anything
			return Response.status(Status.NOT_FOUND)
					.entity("session doesn't exist").build();
		}
		checkWriteAuthorization(sc.getUserPrincipal().getName(), id);
		getHibernate().session().merge(session);

		// more fine-grained events are needed, like "job added" and "dataset removed"
		Events.broadcast(new SessionEvent(id, EventType.UPDATE));
		return Response.noContent().build();
    }

	@DELETE
    @Path("{id}")
	@Transaction
    public Response delete(@PathParam("id") String id, @Context SecurityContext sc) {

		Authorization auth = checkWriteAuthorization(sc.getUserPrincipal().getName(), id);
		// this will delete also the referenced datasets and jobs
		getHibernate().session().delete(auth);
		//Hibernate.session().delete(Hibernate.session().load(Session.class, id));

		Events.broadcast(new SessionEvent(id, EventType.DELETE));
		return Response.noContent().build();
    }
	
	public Authorization checkReadAuthorization(String username, String sessionId) {
    	return checkAuthorization(username, sessionId, false);
    }
	public Authorization checkWriteAuthorization(String username, String sessionId) {
    	return checkAuthorization(username, sessionId, true);
    }
    
	private Authorization checkAuthorization(String username, String sessionId, boolean requireReadWrite) {

		if(username == null) {
			throw new NotAuthorizedException(Response.noContent());
		}
		Session session = (Session) getHibernate().session().get(Session.class, sessionId);
		Object authObj = getHibernate().session().createQuery(
				"from Authorization"
				+ " where username=:username and session=:session")
				.setParameter("username", username)
				.setParameter("session", session).uniqueResult();
		if (authObj == null) {
			// Either the session doesn't exist or the user doesn't have access 
			// rights to it. HTTP specifation allows 404 response in either case,
			// so there is no need to make extra queries to find out.
			throw new NotFoundException();
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
	
	public Hibernate getHibernate() {
		return hibernate;
	}
}
