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

import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.News;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;

@Path("news")
public class NewsApi {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;
	private PubSubServer events;
	
	public NewsApi(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}
    
    public List<News> getAll() {		
			
		CriteriaBuilder cb = hibernate.session().getCriteriaBuilder();
		CriteriaQuery<News> c = cb.createQuery(News.class);
		c.from(News.class);
		List<News> newsList = hibernate.getEntityManager().createQuery(c).getResultList();
		
		return newsList;
	}
		
    public void save(News news) {
		
		HibernateUtil.persist(news, hibernate.session());
		
//		SessionEvent event = new SessionEvent(id, ResourceType.NOTIFICATION, null, EventType.CREATE, null);
//		publish(sessionId.toString(), event, hibernate.session());
    }
	
    public void update(News news) {
		
		HibernateUtil.update(news, news.getNewsId(), hibernate.session());
		
//		SessionEvent event = new SessionEvent(notificationId, ResourceType.NOTIFICATION, null, EventType.UPDATE, null);
//		publish(sessionId.toString(), event, hibernate.session());
    }	
	
	public News getNews(UUID newsId) {

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

    public void delete(UUID id) {

		News dbNews = getNews(id);
		
		HibernateUtil.delete(dbNews, id, hibernate.session());
		
//		SessionEvent event = new SessionEvent(notificationId, ResourceType.NOTIFICATION, null, EventType.DELETE, null);
//		publish(sessionId.toString(), event, hibernate.session());
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
