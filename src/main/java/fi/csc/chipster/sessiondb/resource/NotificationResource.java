package fi.csc.chipster.sessiondb.resource;

import java.util.List;
import java.util.UUID;

import javax.persistence.NoResultException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.BaseSessionEventListener;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.Notification;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

@Path("notifications")
public class NotificationResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;
	private PubSubServer events;
	
	public NotificationResource(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}
    
	@GET
	@RolesAllowed({ Role.UNAUTHENTICATED, Role.CLIENT }) // anyone
    @Produces(MediaType.APPLICATION_JSON)	
	@Transaction
    public List<Notification> getAll() {		
			
		CriteriaBuilder cb = hibernate.session().getCriteriaBuilder();
		CriteriaQuery<Notification> c = cb.createQuery(Notification.class);
		c.from(Notification.class);
		List<Notification> notifications = hibernate.getEntityManager().createQuery(c).getResultList();
		
		return notifications;
	}
		
	@POST
	@RolesAllowed({ Role.ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response post(Notification notification, @Context UriInfo uriInfo, @Context SecurityContext sc) {

		//FIXME 
		// decide sessionId on the server
//		if (notification.getNotificationId() != null) {
//			throw new BadRequestException("session already has an id, post not allowed");
//		}
//
//		UUID id = RestUtils.createUUID();
//		notification.setNotificationId(id);
//		
//		HibernateUtil.persist(notification, hibernate.session());
//		
//		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();
//		
//		ObjectNode json = new JsonNodeFactory(false).objectNode();
//		json.put("notificationId", id.toString());
//		
////		SessionEvent event = new SessionEvent(id, ResourceType.NOTIFICATION, null, EventType.CREATE, null);
////		publish(sessionId.toString(), event, hibernate.session());
//		
//		return Response.created(uri).entity(json).build();
		return null;
    }
	
	@PUT
	@Path("{id}")
	@RolesAllowed({ Role.ADMIN }) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response put(Notification requestNotification, @PathParam("id") UUID notificationId, @Context SecurityContext sc) {
		
				    				
//		// override the url in json with the id in the url, in case a 
//		// malicious client has changed it
//		requestNotification.setNotificationId(notificationId);
//		
//		// check the notification exists (is this needed?)
//		getNotification(notificationId);
//		
//		HibernateUtil.update(requestNotification, notificationId, hibernate.session());
//		
////		SessionEvent event = new SessionEvent(notificationId, ResourceType.NOTIFICATION, null, EventType.UPDATE, null);
////		publish(sessionId.toString(), event, hibernate.session());
//				
//		return Response.noContent().build();
		
		return null;
    }	
	
	private Notification getNotification(UUID notificationId) {

		try {
			CriteriaBuilder cb = hibernate.session().getCriteriaBuilder();
			CriteriaQuery<Notification> c = cb.createQuery(Notification.class);
			Root<Notification> r = c.from(Notification.class);		
			c.where(cb.equal(r.get("notificationId"), notificationId));		
			return hibernate.getEntityManager().createQuery(c).getSingleResult();
			
		} catch (NoResultException e) {
			throw new NotFoundException("notification not found");
		}
	}

	@DELETE
    @Path("{id}")
	@RolesAllowed({ Role.ADMIN })
	@Transaction
    public Response delete(@PathParam("id") UUID id, @Context SecurityContext sc) {

		Notification dbNotification = getNotification(id);
		
		HibernateUtil.delete(dbNotification, id, hibernate.session());
		
//		SessionEvent event = new SessionEvent(notificationId, ResourceType.NOTIFICATION, null, EventType.DELETE, null);
//		publish(sessionId.toString(), event, hibernate.session());
		
		return Response.noContent().build();
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
				// publish the original event
				events.publish(topic, obj);
				
				// global topics for servers
				if (ResourceType.JOB == obj.getResourceType()) {
					events.publish(SessionDbTopicConfig.JOBS_TOPIC, obj);
				}
				// global AUTHORIZATIONS_TOPIC and SESSIONS_TOPIC and DATASETS_TOPIC hasn't been needed yet
			}				
		});	
	}
}
