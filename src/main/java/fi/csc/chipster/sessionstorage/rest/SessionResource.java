package fi.csc.chipster.sessionstorage.rest;

import java.io.IOException;
import java.net.URI;
import java.util.List;
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
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.glassfish.jersey.media.sse.EventOutput;
import org.glassfish.jersey.media.sse.SseFeature;

import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.chipster.sessionstorage.model.SessionEvent;
import fi.csc.chipster.sessionstorage.model.SessionEvent.EventType;

@Path("sessions")
public class SessionResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(SessionResource.class.getName());
	
	// sub-resource locators
	@Path("{id}/datasets")
	public Object getDatasetResource(@PathParam("id") String id) {
		return new DatasetResource(id);
	}
	
	@Path("{id}/jobs")
	public Object getJobResource(@PathParam("id") String id) {
		return new JobResource(id);
	}
	
	// notifications
    @GET
    @Path("{id}/events")
    @Produces(SseFeature.SERVER_SENT_EVENTS)
    public EventOutput listenToBroadcast() {
        return Events.getEventOutput();
    }
	
    // CRUD
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") String id) throws IOException {
    	    
		Hibernate.beginTransaction();
    	Session result = (Session) Hibernate.session().get(Session.class, id);    	
    	Hibernate.commit();
    	
    	if (result == null) {
    		throw new NotFoundException();
    	}	
    	return Response.ok(result).build();    	
    }
    
	@GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAll() {

		Hibernate.beginTransaction();
		@SuppressWarnings("unchecked")
		List<Session> result = Hibernate.session().createQuery("from Session").list();
		Hibernate.commit();

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(result)).build();
    }	

	@POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response post(Session session, @Context UriInfo uriInfo) {
		
		session = RestUtils.getRandomSession();
		session.setSessionId(null);
    	        	
		if (session.getSessionId() != null) {
			throw new BadRequestException("session already has an id, post not allowed");
		}

		session.setSessionId(RestUtils.createId());

		Hibernate.beginTransaction().save(session);
		Hibernate.commit();

		URI uri = uriInfo.getAbsolutePathBuilder().path(session.getSessionId()).build();
		Events.broadcast(new SessionEvent(session.getSessionId(), EventType.CREATE));
		return Response.created(uri).build();
    }

	@PUT
	@Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response put(Session session, @PathParam("id") String id) {
				    		
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		session.setSessionId(id);
		Hibernate.beginTransaction();
		if (Hibernate.session().get(Session.class, id) == null) {
			// transaction will commit, but we haven't changed anything
			return Response.status(Status.NOT_FOUND)
					.entity("session doesn't exist").build();
		}
		Hibernate.session().merge(session);
		Hibernate.commit();

		// more fine-grained events are needed, like "job added" and "dataset removed"
		Events.broadcast(new SessionEvent(id, EventType.UPDATE));
		return Response.noContent().build();
    }

	@DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id) {

		Hibernate.beginTransaction();
		// this will delete also the referenced datasets and jobs
		Hibernate.session().delete(Hibernate.session().load(Session.class, id));
		Hibernate.commit();

		Events.broadcast(new SessionEvent(id, EventType.DELETE));
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
	private GenericEntity<List<Session>> toJaxbList(List<Session> result) {
		return new GenericEntity<List<Session>>(result) {};
	}
}
