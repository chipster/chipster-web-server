package fi.csc.chipster.sessiondb.resource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Root;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.BaseSessionEventListener;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.DatasetIdPair;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.JobIdPair;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.sessiondb.model.SessionState;
import jakarta.ws.rs.ForbiddenException;

public class SessionDbApi {
	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;
	private RuleTable ruleTable;
	private PubSubServer events;

	public SessionDbApi(HibernateUtil hibernate, RuleTable ruleTable) {
		this.hibernate = hibernate;
		this.ruleTable = ruleTable;

	}

	
		public List<Rule> deleteRulesWithUser(String username) {
		// 1) own rule only -> delete rule
		// 2) own rule and shared ro -> delete all rules (own and ro)
		// 3) own rule, shared rw by me, delete own and shared by me
		// 4) shared ro to me, delete shared to me
		// 5) shared rw to me, delete shared to me
		//
		// As a summary, delete all rules where username or sharedBy is me
		// (and delete session if no rules left)

		List<Rule> rulesToDelete = new ArrayList<Rule>();

		// get sessions I have shared to someone 
		List<Rule> sharedByMeRules = ruleTable.getShares(username);
		rulesToDelete.addAll(sharedByMeRules);
		
		
		// get sessions I have access to, except those shared to 'everyone'
		List<Rule> ownRules = ruleTable.getRulesOwn(username);
		rulesToDelete.addAll(ownRules);
		
		for (Rule rule: rulesToDelete) {
			deleteRule(rule.getSession(), rule, hibernate.session(), true);
		}
		
		return rulesToDelete;
	}
	

	
	public void deleteRule(Session session, Rule rule, org.hibernate.Session hibernateSession,
			boolean deleteSessionIfLastRule) {

		ruleTable.delete(session.getSessionId(), rule, hibernate.session());

		// why session.getRules() complains: failed to lazily initialize a collection,
		// could not initialize proxy - no Session?
		List<Rule> sessionRules = ruleTable.getRules(session.getSessionId());

		publishRuleEvent(session.getSessionId(), sessionRules, rule, EventType.DELETE);

		if (deleteSessionIfLastRule) {
			deleteSessionIfOrphan(session);
		}

	}

	public void publishRuleEvent(UUID sessionId, Collection<Rule> sessionRules, Rule rule, EventType eventType) {

		publish(SessionDbTopicConfig.SESSIONS_TOPIC_PREFIX + sessionId.toString(), new SessionEvent(sessionId, ResourceType.RULE, rule.getRuleId(), eventType),
				hibernate.session());

		Set<String> usernames = sessionRules.stream()
				// don't inform about the example sessions
				.filter(r -> !RuleTable.EVERYONE.equals(r.getUsername())).map(r -> r.getUsername())
				.collect(Collectors.toSet());

		// when session is being shared, the recipient is not in the session yet
		usernames.add(rule.getUsername());

		// send events to username topics to update the session list
		for (String username : usernames) {
			publish(SessionDbTopicConfig.USERS_TOPIC_PREFIX + username, new SessionEvent(sessionId, ResourceType.RULE, rule.getRuleId(), eventType),
					hibernate.session());
		}
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
					events.publish(SessionDbTopicConfig.ALL_JOBS_TOPIC, obj);
				}
				// global AUTHORIZATIONS_TOPIC and SESSIONS_TOPIC and DATASETS_TOPIC hasn't been
				// needed yet
			}
		});
	}
	
	public void publishAllTopics(SessionEvent event, org.hibernate.Session hibernateSession, Set<String> topicsToSkip) {
		// publish the event only after the transaction is completed to make
		// sure that the modifications are visible
		hibernateSession.addEventListeners(new BaseSessionEventListener() {
			@Override
			public void transactionCompletion(boolean successful) {
				events.publishAllTopics(event, topicsToSkip);
			}
		});
	}

	public void deleteSessionIfOrphan(Session session) {

		// don't count public read-only rules, because those can't be deleted afterwards
		long count = ruleTable.getRules(session.getSessionId()).stream()
				.filter(r -> r.isReadWrite() && !RuleTable.EVERYONE.equals(r.getUsername())).count();

		if (count == 0) {
			logger.debug("last rule deleted, delete the session too");
			deleteSession(session, hibernate.session());
		} else {
			logger.debug(count + " rules left, session kept");
		}
	}

	public void deleteSession(Session session, org.hibernate.Session hibernateSession) {

		UUID sessionId = session.getSessionId();

		/*
		 * Run in separate transaction so that others will see the state change
		 * immediately. The method sessionFactory.getCurrentSession() will still return
		 * the original session, so be careful to use the innerSession only.
		 */
		HibernateUtil.runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(org.hibernate.Session innerSession) {
				setSessionState(session, SessionState.DELETE, innerSession);
				return null;
			}
		}, hibernate.getSessionFactory(), false);

		/*
		 * All datasets have to be removed first, because the dataset owns the reference
		 * between the dataset and the session. This also generates the necessary events
		 * e.g. to remove files.
		 */
		for (Dataset dataset : SessionDbApi.getDatasets(hibernateSession, session)) {
			deleteDataset(dataset, sessionId, hibernateSession);
		}

		// see the note about datasets above
		for (Job job : getJobs(hibernateSession, session)) {
			deleteJob(job, sessionId, hibernateSession);
		}

		// see the note about datasets above
		for (Rule rule : ruleTable.getRules(sessionId)) {
			// argument "deleteSessionIfLastRule" is set to false, because it would call
			// this method again
			deleteRule(session, rule, hibernate.session(), false);
		}

		HibernateUtil.delete(session, session.getSessionId(), hibernateSession);

		SessionEvent event = new SessionEvent(sessionId, ResourceType.SESSION, null, EventType.DELETE,
				session.getState());
		publish(SessionDbTopicConfig.SESSIONS_TOPIC_PREFIX + sessionId.toString(), event, hibernateSession);
	}

	public void setSessionState(Session session, SessionState state, org.hibernate.Session hibernateSession) {
		session.setState(state);
		// otherwise Hibernate won't recognize the change
		hibernateSession.detach(session);
		this.updateSession(session, hibernateSession);
	}

	public void updateSession(Session session, org.hibernate.Session hibernateSession) {
		UUID sessionId = session.getSessionId();
		// persist
		HibernateUtil.update(session, session.getSessionId(), hibernateSession);

		SessionEvent event = new SessionEvent(sessionId, ResourceType.SESSION, sessionId, EventType.UPDATE,
				session.getState());
		publish(SessionDbTopicConfig.SESSIONS_TOPIC_PREFIX + sessionId.toString(), event, hibernateSession);
	}

	public List<Rule> getRules(UUID sessionId) {
		return ruleTable.getRules(sessionId);
	}

	public void setPubSubServer(PubSubServer pubSubServer) {
		this.events = pubSubServer;
	}

	public void deleteDataset(Dataset dataset, UUID sessionId, org.hibernate.Session hibernateSession) {

		// FIXME use parameter hibernateSession?
		HibernateUtil.delete(dataset, dataset.getDatasetIdPair(), hibernate.session());

		if (dataset.getFile() != null && dataset.getFile().getFileId() != null) {
			UUID fileId = dataset.getFile().getFileId();

			@SuppressWarnings("unchecked")
			List<Dataset> fileDatasets = hibernateSession.createQuery("from Dataset where file=:file")
					.setParameter("file", dataset.getFile()).list();

			// don't care about the dataset that is being deleted
			// why do we still see it?
			fileDatasets.remove(dataset);

			// there isn't anymore anyone using this file and the file-broker
			// can delete it
			if (fileDatasets.isEmpty()) {
				// remove from file-broker
				publish(SessionDbTopicConfig.ALL_FILES_TOPIC,
						new SessionEvent(sessionId, ResourceType.FILE, fileId, EventType.DELETE), hibernateSession);
				// remove from db
				HibernateUtil.delete(dataset.getFile(), dataset.getFile().getFileId(), hibernateSession);
			}

		}
		publish(SessionDbTopicConfig.SESSIONS_TOPIC_PREFIX + sessionId.toString(),
				new SessionEvent(sessionId, ResourceType.DATASET, dataset.getDatasetId(), EventType.DELETE),
				hibernateSession);
	}

	public void deleteJob(Job job, UUID sessionId, org.hibernate.Session hibernateSession) {
		HibernateUtil.delete(job, job.getJobIdPair(), hibernateSession);
		SessionEvent event = new SessionEvent(sessionId, ResourceType.JOB, job.getJobId(), EventType.DELETE,
				job.getState());
		publish(SessionDbTopicConfig.SESSIONS_TOPIC_PREFIX + sessionId.toString(), event, hibernateSession);
	}

	/**
	 * Get requested dataset
	 * 
	 * This must return only dataset if it really is in the session of sesssionId.
	 * Having the both IDs in the query verifies this in the current DB schema.
	 * RuleTable.checkDatasetAuthorization() trusts this.
	 * 
	 * Junit test in SessionDatasetResourceTest.get() follows that this assumption
	 * is not broken by accident.
	 * 
	 * @param sessionId
	 * @param datasetId
	 * @param hibernateSession
	 * @return
	 */
	public static Dataset getDataset(UUID sessionId, UUID datasetId, org.hibernate.Session hibernateSession) {

		Dataset dataset = hibernateSession.get(Dataset.class, new DatasetIdPair(sessionId, datasetId));
		return dataset;
	}

	public static List<Dataset> getDatasets(org.hibernate.Session hibernateSession, Session session) {

		CriteriaBuilder cb = hibernateSession.getCriteriaBuilder();
		CriteriaQuery<Dataset> c = cb.createQuery(Dataset.class);
		Root<Dataset> r = c.from(Dataset.class);
		r.fetch("file", JoinType.LEFT);
		c.select(r);
		c.where(cb.equal(r.get("datasetIdPair").get("sessionId"), session.getSessionId()));
		List<Dataset> datasets = HibernateUtil.getEntityManager(hibernateSession).createQuery(c).getResultList();

		return datasets;
	}

	public void updateDataset(Dataset newDataset, Dataset dbDataset, UUID sessionId,
			org.hibernate.Session hibernateSession) {

		if (newDataset.getFile() != null) {
			if (dbDataset.getFile() == null) {
				HibernateUtil.persist(newDataset.getFile(), hibernateSession);
			} else {
				HibernateUtil.update(newDataset.getFile(), newDataset.getFile().getFileId(), hibernateSession);
			}
		}

		HibernateUtil.update(newDataset, newDataset.getDatasetIdPair(), hibernateSession);
		publish(SessionDbTopicConfig.SESSIONS_TOPIC_PREFIX + sessionId.toString(),
				new SessionEvent(sessionId, ResourceType.DATASET, newDataset.getDatasetId(), EventType.UPDATE),
				hibernateSession);
	}

	public void createDataset(Dataset dataset, UUID sessionId, org.hibernate.Session hibernateSession) {

		checkFileModification(dataset, hibernateSession);

		if (dataset.getFile() != null) {
			// why CascadeType.PERSIST isn't enough?
			HibernateUtil.persist(dataset.getFile(), hibernateSession);
		}
		HibernateUtil.persist(dataset, hibernateSession);
		publish(SessionDbTopicConfig.SESSIONS_TOPIC_PREFIX + sessionId.toString(),
				new SessionEvent(sessionId, ResourceType.DATASET, dataset.getDatasetId(), EventType.CREATE),
				hibernateSession);
	}

	public void checkFileModification(Dataset dataset, org.hibernate.Session hibernateSession) {
		// if the file exists, don't allow it to be modified
		if (dataset.getFile() == null || dataset.getFile().isEmpty()) {
			return;
		}
		File dbFile = hibernateSession.get(File.class, dataset.getFile().getFileId());
		if (dbFile != null) {
			if (!dbFile.equals(dataset.getFile())) {
				throw new ForbiddenException("modification of existing file is forbidden");
			}
			dataset.setFile(dbFile);
		}
	}

	public static Job getJob(UUID sessionId, UUID jobId, org.hibernate.Session hibernateSession) {

		return hibernateSession.get(Job.class, new JobIdPair(sessionId, jobId));
	}

	public static List<Job> getJobs(org.hibernate.Session hibernateSession, Session session) {

		CriteriaBuilder cb = hibernateSession.getCriteriaBuilder();
		CriteriaQuery<Job> c = cb.createQuery(Job.class);
		Root<Job> r = c.from(Job.class);
		c.select(r);
		c.where(cb.equal(r.get("jobIdPair").get("sessionId"), session.getSessionId()));
		List<Job> datasets = HibernateUtil.getEntityManager(hibernateSession).createQuery(c).getResultList();

		return datasets;
	}

	public void createJob(Job job, UUID sessionId, org.hibernate.Session hibernateSession) {
		HibernateUtil.persist(job, hibernateSession);
		SessionEvent event = new SessionEvent(sessionId, ResourceType.JOB, job.getJobId(), EventType.CREATE,
				job.getState());
		publish(SessionDbTopicConfig.SESSIONS_TOPIC_PREFIX + sessionId.toString(), event, hibernateSession);
	}

	public void updateJob(Job job, UUID sessionId, org.hibernate.Session hibernateSession) {
		HibernateUtil.update(job, job.getJobIdPair(), hibernateSession);
		SessionEvent event = new SessionEvent(sessionId, ResourceType.JOB, job.getJobId(), EventType.UPDATE,
				job.getState());
		publish(SessionDbTopicConfig.SESSIONS_TOPIC_PREFIX + sessionId.toString(), event, hibernateSession);
	}

	public void createSession(Session session, Rule auth, org.hibernate.Session hibernateSession) {
		HibernateUtil.persist(session, hibernateSession);

		createRule(auth, session);

		UUID sessionId = session.getSessionId();
		SessionEvent event = new SessionEvent(sessionId, ResourceType.SESSION, sessionId, EventType.CREATE,
				session.getState());
		publish(SessionDbTopicConfig.SESSIONS_TOPIC_PREFIX + sessionId.toString(), event, hibernateSession);
	}

	public UUID createRule(Rule newRule, Session session) {

		newRule.setRuleId(RestUtils.createUUID());

		// make sure a hostile client doesn't set the session
		newRule.setSession(session);

		newRule.setCreated(Instant.now());

		ruleTable.save(newRule, hibernate.session());

		publishRuleEvent(session.getSessionId(), session.getRules(), newRule, EventType.CREATE);

		return newRule.getRuleId();
	}

	public List<Session> getSessions(String userId) {
		@SuppressWarnings("unchecked")
		List<Rule> rules = hibernate.session().createQuery("from Rule where username=:username")
				.setParameter("username", userId).list();

		List<Session> sessions = rules.stream().map(rule -> rule.getSession()).collect(Collectors.toList());
		return sessions;
	}
	
	public void sessionModified(Session session, org.hibernate.Session hibernateSession) {
		if (SessionState.TEMPORARY_UNMODIFIED == session.getState()) {
			setSessionState(session, SessionState.TEMPORARY_MODIFIED, hibernateSession);
		}
	}
}
