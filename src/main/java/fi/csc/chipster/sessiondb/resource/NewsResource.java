package fi.csc.chipster.sessiondb.resource;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.persistence.NoResultException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.BaseSessionEventListener;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.News;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
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

@Path("news")
public class NewsResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;
	private PubSubServer events;
	
	public NewsResource(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}
    
	@GET
	@RolesAllowed({ Role.UNAUTHENTICATED, Role.CLIENT }) // anyone
    @Produces(MediaType.APPLICATION_JSON)	
	@Transaction
    public List<News> getAll() {		
			
		CriteriaBuilder cb = hibernate.session().getCriteriaBuilder();
		CriteriaQuery<News> c = cb.createQuery(News.class);
		c.from(News.class);
		List<News> newsList = hibernate.getEntityManager().createQuery(c).getResultList();
		
		return newsList;
	}
		
	@POST
	@RolesAllowed({ Role.ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response post(News news, @Context UriInfo uriInfo, @Context SecurityContext sc) {

		// decide sessionId on the server
		if (news.getNewsId() != null) {
			throw new BadRequestException("session already has an id, post not allowed");
		}

		UUID id = RestUtils.createUUID();
		news.setNewsId(id);
		news.setCreated(Instant.now());
		
		HibernateUtil.persist(news, hibernate.session());
		
		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();
		
		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("notificationId", id.toString());
		
//		SessionEvent event = new SessionEvent(id, ResourceType.NOTIFICATION, null, EventType.CREATE, null);
//		publish(sessionId.toString(), event, hibernate.session());
		
		return Response.created(uri).entity(json).build();
    }
	
	@PUT
	@Path("{id}")
	@RolesAllowed({ Role.ADMIN }) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response put(News requestNews, @PathParam("id") UUID newsId, @Context SecurityContext sc) {
		
				    				
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		requestNews.setNewsId(newsId);
		requestNews.setModified(Instant.now());
		
		// check the notification exists (is this needed?)
		News dbNotification = getNews(newsId);
		
		requestNews.setCreated(dbNotification.getCreated());
		
		HibernateUtil.update(requestNews, newsId, hibernate.session());
		
//		SessionEvent event = new SessionEvent(notificationId, ResourceType.NOTIFICATION, null, EventType.UPDATE, null);
//		publish(sessionId.toString(), event, hibernate.session());
				
		return Response.noContent().build();
    }	
	
	private News getNews(UUID newsId) {

		try {
			CriteriaBuilder cb = hibernate.session().getCriteriaBuilder();
			CriteriaQuery<News> c = cb.createQuery(News.class);
			Root<News> r = c.from(News.class);		
			c.where(cb.equal(r.get("newsId"), newsId));		
			return hibernate.getEntityManager().createQuery(c).getSingleResult();
			
		} catch (NoResultException e) {
			throw new NotFoundException("news not found");
		}
	}

	@DELETE
    @Path("{id}")
	@RolesAllowed({ Role.ADMIN })
	@Transaction
    public Response delete(@PathParam("id") UUID id, @Context SecurityContext sc) {

		News dbNews = getNews(id);
		
		HibernateUtil.delete(dbNews, id, hibernate.session());
		
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
