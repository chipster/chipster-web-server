package fi.csc.chipster.sessiondb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.hibernate.Query;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.Session;

import com.fasterxml.jackson.core.JsonParseException;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.model.Authorization;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.sessiondb.model.TableStats;
import fi.csc.chipster.sessiondb.resource.AuthorizationResource;
import fi.csc.chipster.sessiondb.resource.SessionDbAdminResource;
import fi.csc.chipster.sessiondb.resource.SessionResource;

public class SessionDbCluster implements SessionEventListener {
	
	public static final org.apache.logging.log4j.Logger logger = LogManager.getLogger();
	
	private volatile boolean queueEvents = true;
	private LinkedList<SessionEvent> eventQueue = new LinkedList<>();

	private SessionDbClient sourceSessionDbClient;
	private SessionResource targetSessionResource;
	private SessionDbAdminResource adminResource;

	private HibernateUtil hibernate;

	/**
	 * To enable replication, set following settings for example in environment variables. 
	 * Only the first one is needed, if the master and slave instances run on different hosts.
	 * 
	 * session-db-replicate=	"true"
	 * session-db-bind=			"http://localhost:8070/sessiondb/"
	 * session-db-events-bind=	"http://localhost:8074/sessiondbevents/"
	 * session-db-name=			"session-db-replicate"
	 * 
	 * @param serviceLocator
	 * @param authService
	 * @param authorizationResource
	 * @param sessionResource
	 * @param adminResource 
	 * @param hibernate
	 * @param sessionDb
	 * @throws RestException
	 */
	public void replicate(ServiceLocatorClient serviceLocator, AuthenticationClient authService, AuthorizationResource authorizationResource, SessionResource sessionResource, SessionDbAdminResource adminResource, HibernateUtil hibernate, SessionDb sessionDb) throws RestException {

		this.sourceSessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials());
		this.targetSessionResource = sessionResource;
		this.adminResource = adminResource;
		this.hibernate = hibernate;
		
		// listen for authorization updates
		sourceSessionDbClient.subscribe(SessionDb.AUTHORIZATIONS_TOPIC, this, "replication-authorization-listener");
		sourceSessionDbClient.subscribe(SessionDb.SESSIONS_TOPIC, this, "replication-session-listener");
		sourceSessionDbClient.subscribe(SessionDb.DATASETS_TOPIC, this, "replication-dataset-listener");
		sourceSessionDbClient.subscribe(SessionDb.JOBS_TOPIC, this, "replication-job-listener");
		
		logger.info("bulk replication started");
		print("  master rows ", sourceSessionDbClient.getTableStats());
		
		try {
			replicateSessions(authorizationResource, sessionResource, hibernate, sourceSessionDbClient);
			replicateDatasetsAndJobs(authorizationResource, sessionResource, hibernate, sourceSessionDbClient);
		} catch (IOException e) {
			throw new RestException("bulk replication failed", e);
		}
		logger.info("replicating latest changes");
		
		// first handle old events 
		handleQueue();
		
		queueEvents = false;
		
		// handle events received when handling the queue 
		handleQueue();
		
		print("  master rows ", sourceSessionDbClient.getTableStats());
		printTargetTableStats();
		
		logger.info("bulk replication completed, following changes on the master");
	}

	private void printTargetTableStats() {
		hibernate.runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(Session hibernateSession) {
				print("  slave rows  ", adminResource.getTableStats(hibernateSession));
				return null;
			}
		});
	}

	private void print(String msg, List<TableStats> list) {
		
		List<TableStats> sortedList = new ArrayList<>(list);
				
		sortedList.sort(new Comparator<TableStats>() {
			@Override
			public int compare(TableStats o1, TableStats o2) {
				return o1.getName().compareTo(o2.getName());
			}
		});
		
		for (TableStats table : sortedList) {
			msg += table.getName() + ": " + table.getSize() + " ";
		}
		logger.info(msg);
	}

	private void replicateSessions(AuthorizationResource authorizationResource, SessionResource sessionResource, HibernateUtil hibernate,
			SessionDbClient source) throws RestException, JsonParseException, IOException {

		int sessionCount = 0;

		// actually we copy and authorization, which includes the session
		Iterator<Authorization> authorizations = source.getAuthorizations();

		while (authorizations.hasNext()) {
			Authorization authorization = authorizations.next();

			hibernate.runInTransaction(new HibernateRunnable<Void>() {

				@Override
				public Void run(Session hibernateSession) {
					authorizationResource.save(authorization, hibernateSession);
					return null;
				}
			});

			sessionCount ++;
			if (sessionCount % 1000 == 0) {
				printTargetTableStats();
			}
		}
	}

	private void replicateDatasetsAndJobs(AuthorizationResource authorizationResource, SessionResource sessionResource, HibernateUtil hibernate,
			SessionDbClient source) throws RestException, JsonParseException, IOException {

		/*
		 * We need separate transactions for reading and writing. One read transaction
		 * is used for iterating through the whole table. A new write transaction is created
		 * for each session to avoid running out of memory.
		 */
		
		hibernate.runInTransaction(new HibernateRunnable<Void>() {

			@Override
			public Void run(Session hibernateSession) {

				try {
					int sessionCount = 0;

					Query query = hibernateSession.createQuery("from Authorization");
					query.setReadOnly(true);

					ScrollableResults results = query.scroll(ScrollMode.FORWARD_ONLY);

					// iterate over results
					while (results.next()) {
						// process row then release reference
						Authorization authorization = (Authorization) results.get()[0];
							replicateSession(source, authorization);

						sessionCount++;
						if (sessionCount % 100 == 0) {
							printTargetTableStats();
						}						
					}
				} catch (RestException e) {
					logger.error("replication failed", e);
					//TODO interrupt startup
				}

				return null;
			}
		});
	}
	
	private void replicateSession(SessionDbClient source, Authorization authorization)
			throws RestException {
		UUID sessionId = authorization.getSession().getSessionId();

		HashMap<UUID, Dataset> datasets;
		datasets = source.getDatasets(sessionId);
		HashMap<UUID, Job> jobs = source.getJobs(sessionId);

		hibernate.runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(Session hibernateSession) {
				for (Dataset dataset : datasets.values()) {
					if (dataset.getFile() != null) {
						// why cascade doesn't handle this?
						hibernateSession.save(dataset.getFile());
					}
					hibernateSession.save(dataset);
				}

				for (Job job : jobs.values()) {
					hibernateSession.save(job);
				}
				return null;
			}
		});
	}
	
	private void handleQueue() throws RestException {
		synchronized (eventQueue) {
			while (!eventQueue.isEmpty()) {
				hibernate.runInTransaction(new HibernateRunnable<Void>() {
					@Override
					public Void run(Session hibernateSession) {
						try {
							handleEvent(eventQueue.removeFirst(), hibernateSession);
						} catch (RestException e) {
							logger.error("failed to handle queue event", e);
							// this should interrupt the startup
						}
						return null;
					}
				});
			}
		}
	}

	@Override
	public void onEvent(SessionEvent e) {
		if (queueEvents) {
			synchronized (eventQueue) {
				eventQueue.add(e);
			}
		} else {
			
//			System.out.println("handling event " + e.getType() + " " + e.getResourceType() + " " + e.getResourceId());
			
			/* 
			 * The DB doesn't allow us to create datasets or jobs before the session is created.
			 * 
			 * For example, a session import is quite likely to cause problems:
			 * 
			 * 1. client posts a session
			 * 2. master sends the http response
			 * 2.1 client posts a dataset
			 * 2.2 master published the dataset created event
			 * 2.3 slave receives the dataset created event and gets the dataset from the master
			 * 2.4 slave tries to save the dataset, but DB complains that the referenced session doesn't exist yet
			 * 3. master publishes the session created event
			 * 3.1 slave receives the session created event and gets the session from the master
			 * 3.2 slave saves the session
			 * 
			 * Without this check the DB will complain, if the events in the 2.x branch advance faster than the events in the 3.x branch.
			 * 
			 * Alternatively, we could remove the foreign key constraint from the database. That would match well with our eventually
			 * consistent replication model, but the database constraint has been a very useful safety check when configuring Hibernate's 
			 * collection cascades.
			 */
			if (!(e.getType() == EventType.CREATE  && (e.getResourceType() == ResourceType.SESSION || e.getResourceType() == ResourceType.AUTHORIZATION))) {
				waitForSession(e.getSessionId());
			}
			
			hibernate.runInTransaction(new HibernateRunnable<Void>() {
				@Override
				public Void run(Session hibernateSession) {
					try {
						logger.info("replication update: " + 
								e.getType().toString().toLowerCase() + " " + 
								e.getResourceType().toString().toLowerCase() + " " + 
								e.getResourceId());
						handleEvent(e, hibernateSession);
					} catch (RestException ex) {
						logger.error("replication error", ex);
					}
					return null;
				}
			});
		}
	}

	private void handleEvent(SessionEvent e, Session hibernateSession) throws RestException {
		
		switch (e.getResourceType()) {
		case DATASET:
			switch(e.getType()) {
			case CREATE:
				createDataset(e.getSessionId(), e.getResourceId(), hibernateSession);
				break;
			case UPDATE:
				updateDataset(e.getSessionId(), e.getResourceId(), hibernateSession);
				break;
			case DELETE:
				deleteDataset(e.getSessionId(), e.getResourceId(), hibernateSession);
				break;
			default:
				throw new IllegalArgumentException("unknown even type " + e.getType());
			}
			break;
		case JOB:
			switch(e.getType()) {
			case CREATE:
				createJob(e.getSessionId(), e.getResourceId(), hibernateSession);
				break;
			case UPDATE:
				updateJob(e.getSessionId(), e.getResourceId(), hibernateSession);
				break;
			case DELETE:
				deleteJob(e.getSessionId(), e.getResourceId(), hibernateSession);
				break;
			default:
				throw new IllegalArgumentException("unknown even type " + e.getType());
			}
			break;
		case AUTHORIZATION:
			switch(e.getType()) {
			case CREATE:
				createSession(e.getResourceId(), hibernateSession);
				break;
			case UPDATE:
				// handled by session
				break;
			case DELETE:
				deleteSession(e.getResourceId(), hibernateSession);
				break;
			default:
				throw new IllegalArgumentException("unknown even type " + e.getType());
			}
			break;
		case SESSION:
			switch(e.getType()) {
			case CREATE:
				// handled by authorization
				break;
			case UPDATE:
				updateSession(e.getSessionId(), hibernateSession);
				break;
			case DELETE:
				// handled by authorization
				break;
			default:
				throw new IllegalArgumentException("unknown even type " + e.getType());
			}
			break;
		case FILE:
			// datasets take care of these
			break;
		default:
			throw new IllegalArgumentException("unknown event resource " + e.getResourceType());
		}
	}

	private void waitForSession(UUID sessionId) {
		try {
			for (int i = 0; i < 10; i++) {
				if (getSession(sessionId) != null) {
					break;
				}
				logger.info("waiting for session to become available " + sessionId);
				Thread.sleep(1000);					
			}
		} catch (InterruptedException e1) {
			logger.warn("wait for session interrupted", e1);
			// don't care
		}
	}
	
	private fi.csc.chipster.sessiondb.model.Session getSession(UUID sessionId) {
		fi.csc.chipster.sessiondb.model.Session session = hibernate.runInTransaction(new HibernateRunnable<fi.csc.chipster.sessiondb.model.Session>() {
			@Override
			public fi.csc.chipster.sessiondb.model.Session run(Session hibernateSession) {
				fi.csc.chipster.sessiondb.model.Session session = hibernateSession.get(fi.csc.chipster.sessiondb.model.Session.class, sessionId);
				return session;
			}
		});
		return session;
	}

	private void createSession(UUID authorizationId, Session hibernateSession) throws RestException {
		Authorization auth = sourceSessionDbClient.getAuthorization(authorizationId);	
		targetSessionResource.create(auth, hibernateSession);
	}

	private void updateSession(UUID sessionId, Session hibernateSession) throws RestException {
		fi.csc.chipster.sessiondb.model.Session session = sourceSessionDbClient.getSession(sessionId);
		targetSessionResource.update(session, hibernateSession);
	}
	
	private void deleteSession(UUID authorizationId, Session hibernateSession) {
		Authorization auth = targetSessionResource.getAuthorizationResource().getAuthorization(authorizationId, hibernateSession);	
		targetSessionResource.deleteSession(auth, hibernateSession);
	}

	private void createJob(UUID sessionId, UUID jobId, Session hibernateSession) throws RestException {
		Job job = sourceSessionDbClient.getJob(sessionId, jobId);
		targetSessionResource.getJobResource(sessionId).create(job, hibernateSession);
	}

	private void updateJob(UUID sessionId, UUID jobId, Session hibernateSession) throws RestException {
		Job job = sourceSessionDbClient.getJob(sessionId, jobId);
		targetSessionResource.getJobResource(sessionId).update(job, hibernateSession);
	}
	private void deleteJob(UUID sessionId, UUID jobId, Session hibernateSession) {
		Job job = targetSessionResource.getJobResource(sessionId).getJob(jobId, hibernateSession);
		if (job != null) {
			targetSessionResource.getJobResource(sessionId).deleteJob(job, hibernateSession);
		} else {
			logger.warn("replicating job delete, but job not found");
		}
	}
	
	private void createDataset(UUID sessionId, UUID datasetId, Session hibernateSession) throws RestException {
		Dataset dataset = sourceSessionDbClient.getDataset(sessionId, datasetId);
		targetSessionResource.getDatasetResource(sessionId).create(dataset, hibernateSession);
	}

	private void updateDataset(UUID sessionId, UUID datasetId, Session hibernateSession) throws RestException {
		Dataset dataset = sourceSessionDbClient.getDataset(sessionId, datasetId);
		targetSessionResource.getDatasetResource(sessionId).update(dataset, hibernateSession);
	}
	private void deleteDataset(UUID sessionId, UUID datasetId, Session hibernateSession) {
		Dataset dataset = targetSessionResource.getDatasetResource(sessionId).getDataset(datasetId, hibernateSession);
		if (dataset != null) {
			targetSessionResource.getDatasetResource(sessionId).deleteDataset(dataset, hibernateSession);
		} else {
			logger.warn("replicating dataset delete, but dataset not found");
		}
	}
}
