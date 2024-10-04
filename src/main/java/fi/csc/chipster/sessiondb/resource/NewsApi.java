package fi.csc.chipster.sessiondb.resource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.NoResultException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.News;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;

@Path("news")
public class NewsApi {

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;

	private SessionDbApi sessionDbApi;

	private Set<String> topicsToSkip = new HashSet<>() {
		{
			add(SessionDbTopicConfig.ALL_JOBS_TOPIC);
			add(SessionDbTopicConfig.ALL_FILES_TOPIC);
		}
	};

	public NewsApi(HibernateUtil hibernate, SessionDbApi sessionDbApi) {
		this.hibernate = hibernate;
		this.sessionDbApi = sessionDbApi;
	}

	public List<News> getAll() {

		CriteriaBuilder cb = hibernate.session().getCriteriaBuilder();
		CriteriaQuery<News> c = cb.createQuery(News.class);
		c.from(News.class);
		List<News> newsList = hibernate.getEntityManager().createQuery(c).getResultList();

		return newsList;
	}

	public void create(News news) {

		HibernateUtil.persist(news, hibernate.session());

		SessionEvent event = new SessionEvent(null, ResourceType.NEWS, news.getNewsId(), EventType.CREATE, null);
		sessionDbApi.publishAllTopics(event, hibernate.session(), this.topicsToSkip);
	}

	public void update(News news) {

		HibernateUtil.update(news, news.getNewsId(), hibernate.session());

		SessionEvent event = new SessionEvent(null, ResourceType.NEWS, news.getNewsId(), EventType.UPDATE, null);
		sessionDbApi.publishAllTopics(event, hibernate.session(), this.topicsToSkip);
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

		SessionEvent event = new SessionEvent(null, ResourceType.NEWS, id, EventType.DELETE, null);
		sessionDbApi.publishAllTopics(event, hibernate.session(), this.topicsToSkip);
	}
}
