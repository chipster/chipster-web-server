package fi.csc.chipster.jobhistory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.hibernate.Session;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.LogType;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServerComponent;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.scheduler.IdPair;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.JobIdPair;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.websocket.MessageHandler;

public class JobHistoryService implements SessionEventListener, MessageHandler, ServerComponent {

	private static final String CONFIG_KEY_JOB_HISTORY_CLEAN_UP_DELAY = "job-history-clean-up-delay";
	private static final String CONFIG_KEY_JOB_HISTORY_CLEAN_UP_INTERVAL = "job-history-clean-up-interval";
	private static final String CONFIG_KEY_JOB_HISTORY_CLEAN_UP_AFTER = "job-history-clean-up-after";
	private static final String CONFIG_KEY_JOB_HISTORY_CLEAN_UP_MAX_RATIO = "job-history-clean-up-max-ratio";

	private Logger logger = LogManager.getLogger();
	private HttpServer httpServer;
	private HttpServer jobHistoryAdminServer;
	private HibernateUtil hibernate;
	private JobHistoryResource jobHistoryResource;
	private Config config;
	private ServiceLocatorClient serviceLocator;
	private AuthenticationClient authService;
	private SessionDbClient sessionDbClient;
	private TokenRequestFilter tokenRequestFilter;

	public JobHistoryService(Config config) {
		this.config = config;
	}

	/**
	 * Starts the Grizzly HTTP Server
	 * 
	 * @throws URISyntaxException
	 * @throws IOException
	 * @throws RestException
	 * @throws InterruptedException
	 */

	public void startServer() throws URISyntaxException, IOException, RestException, InterruptedException {
		// WebSocket connection to Session DB
		String username = Role.JOB_HISTORY;
		String password = this.config.getPassword(username);

		this.serviceLocator = new ServiceLocatorClient(this.config);
		this.authService = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());

		this.tokenRequestFilter = new TokenRequestFilter(authService);

		List<Class<?>> hibernateClasses = Arrays.asList(JobHistory.class);
		// Initializing hibernate components
		hibernate = new HibernateUtil(this.config, Role.JOB_HISTORY, hibernateClasses);

		this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.SERVER);

		// we are ready to handle events after this.hibernate has been set
		this.sessionDbClient.subscribe(SessionDbTopicConfig.ALL_JOBS_TOPIC, this, "job-history");

		this.jobHistoryResource = new JobHistoryResource(hibernate, config);

		// why does this even have this public api server, when all resources are
		// in the admin api server?
		final ResourceConfig rc = RestUtils.getDefaultResourceConfig(this.serviceLocator)
				.register(new HibernateRequestFilter(hibernate))
				.register(new HibernateResponseFilter(hibernate))
				.register(tokenRequestFilter);

		URI baseUri = URI.create(this.config.getBindUrl(Role.JOB_HISTORY));
		httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc, false);
		RestUtils.configureGrizzlyThreads(this.httpServer, Role.JOB_HISTORY, false, config);
		RestUtils.configureGrizzlyRequestLog(this.httpServer, Role.JOB_HISTORY, LogType.API);
		httpServer.start();

		// Starting the Job History Admin Server
		this.jobHistoryAdminServer = RestUtils.startAdminServer(jobHistoryResource, hibernate, Role.JOB_HISTORY, config,
				authService, this.serviceLocator);
		System.out.println("Admin server started");
		this.jobHistoryAdminServer.start();

		try {
			int cleanUpDelay = config.getInt(CONFIG_KEY_JOB_HISTORY_CLEAN_UP_DELAY);
			int cleanUpInterval = config.getInt(CONFIG_KEY_JOB_HISTORY_CLEAN_UP_INTERVAL);
			int deleteAfter = config.getInt(CONFIG_KEY_JOB_HISTORY_CLEAN_UP_AFTER);
			float maxDeletionRatio = config.getFloat(CONFIG_KEY_JOB_HISTORY_CLEAN_UP_MAX_RATIO);

			logger.info("clean-up interval is " + cleanUpInterval + " hours");
			logger.info("delete rows older than " + deleteAfter + " years");
			logger.info("refuse to delete more than " + maxDeletionRatio + " of total rows");

			new Timer(true).schedule(new JobHistoryCleanUp(deleteAfter, maxDeletionRatio),
					cleanUpDelay * 60 * 60 * 1000,
					cleanUpInterval * 60 * 60 * 1000);
		} catch (NumberFormatException e) {
			logger.info("job-history clean-up is not configured");
		}
	}

	class JobHistoryCleanUp extends TimerTask {

		private int deleteAfter;
		private float maxDeletionRatio;

		public JobHistoryCleanUp(int deleteAfter, float maxDeletionRatio) {
			this.deleteAfter = deleteAfter;
			this.maxDeletionRatio = maxDeletionRatio;
		}

		@Override
		public void run() {
			hibernate.runInTransaction(new HibernateRunnable<Void>() {
				public Void run(Session hibernateSession) {
					doCleanUp(hibernateSession);
					return null;
				}
			});
		}

		private void doCleanUp(Session hibernateSession) {

			logger.info("job-history clean-up started");

			Instant deleteBefore = ZonedDateTime.now().minusYears(deleteAfter).toInstant();

			long totalRows = getJobHistoryCount(hibernateSession);
			long rowsToDelete = rowCountOlderThan(deleteBefore, hibernateSession);

			double deletionRatio = (double) rowsToDelete / totalRows;

			logger.info("total rows: " + totalRows);
			logger.info("rows to delete: " + rowsToDelete);
			logger.info("deletion ratio: " + deletionRatio);

			/*
			 * Simple sanity check before deletion
			 * 
			 * Something can change between the previous check and this deletion, but this
			 * should already lower a lot the chances of catastrophical deletion and DB
			 * should be recoverable from backups.
			 */
			if (deletionRatio <= maxDeletionRatio) {
				int rows = deleteOlderThan(deleteBefore, hibernateSession);
				logger.info("deleted rows: " + rows);
			} else {
				logger.error("Refusing to delete rows! Deletion would be larger than "
						+ CONFIG_KEY_JOB_HISTORY_CLEAN_UP_MAX_RATIO);
			}

			logger.info("job-history clean-up done");
		}
	}

	private long getJobHistoryCount(Session hibernateSession) {
		CriteriaBuilder qb = hibernateSession.getCriteriaBuilder();
		CriteriaQuery<Long> cq = qb.createQuery(Long.class);
		cq.select(qb.count(cq.from(JobHistory.class)));
		return hibernate.session().createQuery(cq).getSingleResult();
	}

	private long rowCountOlderThan(Instant time, Session hibernateSession) {

		CriteriaBuilder cb = hibernateSession.getCriteriaBuilder();
		CriteriaQuery<Long> cq = cb.createQuery(Long.class);
		Root<JobHistory> root = cq.from(JobHistory.class);
		cq.select(cb.count(root));
		cq.where(cb.lessThan(root.get("startTime"), time));
		return hibernate.session().createQuery(cq).getSingleResult();
	}

	private int deleteOlderThan(Instant time, Session hibernateSession) {
		CriteriaBuilder criteriaBuilder = hibernateSession.getCriteriaBuilder();
		CriteriaDelete<JobHistory> query = criteriaBuilder.createCriteriaDelete(JobHistory.class);
		Root<JobHistory> root = query
				.from(JobHistory.class);
		query.where(criteriaBuilder.lessThan(root.get("startTime"), time));

		return hibernate.getEntityManager().createQuery(query).executeUpdate();
	}

	private HttpServer getHttpServer() {
		return httpServer;
	}

	public static void main(String[] args) throws URISyntaxException, IOException, RestException, InterruptedException {
		final JobHistoryService jobHistoryService = new JobHistoryService(new Config());
		jobHistoryService.startServer();
		RestUtils.shutdownGracefullyOnInterrupt(jobHistoryService.httpServer, "job-history");
		RestUtils.waitForShutdown("Job History service", jobHistoryService.getHttpServer());

	}

	@Override
	public void onEvent(SessionEvent e) {
		// logger.info("received a job event: " + e.getResourceType() + " " +
		// e.getType());
		try {
			if (e.getResourceType() == ResourceType.JOB) {
				handleDbEvent(e, new IdPair(e.getSessionId(), e.getResourceId()), JobState.valueOf(e.getState()));
			}
		} catch (Exception ex) {
			logger.error("error when handling a session event", ex);
		}

	}

	/**
	 * Handling the events received from session-db
	 * 
	 * @param jobState
	 * 
	 */

	private void handleDbEvent(SessionEvent e, IdPair jobIdPair, JobState jobState) throws RestException {
		switch (e.getType()) {
			case CREATE:
				Job job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());

				// use the state from the event, because the state in db can be already
				// something else
				switch (jobState) {
					case NEW:
						// When a client adds a new job, save it the job history
						// database
						// hibernate.getsession.save()
						saveNewJobHistory(job, jobState);
						break;
					case COMPLETED:
						logger.info("For imported sessions, jobs are not logged in to job history database");
						break;
					default:
						break;
				}
				break;
			case UPDATE:
				updateJobHistory(e.getSessionId(), e.getResourceId(), jobState);
				break;
			default:
				break;
			// what to do with if the client has cancelled the job?
		}
	}

	private void saveNewJobHistory(Job job, JobState jobState) {
		logger.info("saveJobHistory " + job.getCreated() + " " + job.getStartTime() + " " + job.getEndTime());
		JobHistory jobHistory = new JobHistory();
		jobHistory.setJobIdPair(job.getJobIdPair());
		jobHistory.setToolId(job.getToolId());
		jobHistory.setToolName(job.getToolName());
		jobHistory.setModule(job.getModule());

		if (job.getCreated() == null) {
			// session-db should make sure that this is set for all jobs
			logger.warn("job.created is null, using the current time");
			jobHistory.setCreated(Instant.now());
		} else {
			jobHistory.setCreated(job.getCreated());
		}

		jobHistory.setState(jobState.toString());
		jobHistory.setCreatedBy(job.getCreatedBy());

		getHibernate().runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(Session hibernateSession) {
				HibernateUtil.persist(jobHistory, hibernateSession);
				return null;
			}
		});
	}

	private void updateJobHistory(UUID sessionId, UUID jobId, JobState newState) {
		getHibernate().runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(Session hibernateSession) {

				JobHistory js = hibernateSession.find(JobHistory.class, new JobIdPair(sessionId, jobId));
				// the HibbernateUtil.update() assumes detached objects, otherwise it won't
				// notice the changes
				hibernateSession.detach(js);

				boolean isFinished = newState.isFinished();
				boolean isScheduled = JobState.NEW == JobState.valueOf(js.getState())
						&& newState != JobState.NEW;

				if (isFinished || isScheduled) {
					Job job;
					try {
						job = sessionDbClient.getJob(sessionId, jobId);

						js.setStartTime(job.getStartTime());
						js.setEndTime(job.getEndTime());
						js.setScreenOutput(job.getScreenOutput());
						js.setState(job.getState().toString());
						js.setStateDetail(job.getStateDetail());
						js.setMemoryUsage(job.getMemoryUsage());
						js.setStorageUsage(job.getStorageUsage());
						js.setComp(job.getComp());

						if (js.getStateDetail() != null && js.getStateDetail().length() > 255) {
							logger.warn("cutting too long state detail: " + js.getStateDetail());
							js.setStateDetail(js.getStateDetail().substring(0, 255));
						}

						HibernateUtil.update(js, js.getJobIdPair(), hibernateSession);

					} catch (RestException e) {
						logger.error("failed to get the job from session-db", e);
					}
				}
				return null;
			}
		});
	}

	private HibernateUtil getHibernate() {
		return hibernate;
	}

	public void close() {

		try {
			if (sessionDbClient != null) {
				// shutdown websocket first (see ServerLauncher.stop())
				sessionDbClient.close();
			}
			authService.close();
		} catch (IOException e) {
			logger.warn("failed to shutdown session-db client", e);
		}
		hibernate.getSessionFactory().close();

		RestUtils.shutdown("job-history-admin", jobHistoryAdminServer);
		RestUtils.shutdown("job-history", httpServer);
	}
}
