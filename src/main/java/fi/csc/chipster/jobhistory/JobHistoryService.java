package fi.csc.chipster.jobhistory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.websocket.MessageHandler;

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

public class JobHistoryService implements SessionEventListener, MessageHandler {

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
		this.sessionDbClient.subscribe(SessionDbTopicConfig.JOBS_TOPIC, this, "job-history");

		this.jobHistoryResource = new JobHistoryResource(hibernate, config);

		// TODO why does this even have this public api server, when all resources are in the admin api server?
		final ResourceConfig rc = RestUtils.getDefaultResourceConfig(this.serviceLocator)
				.register(new HibernateRequestFilter(hibernate))
				.register(new HibernateResponseFilter(hibernate))
				.register(tokenRequestFilter);

		URI baseUri = URI.create(this.config.getBindUrl(Role.JOB_HISTORY));
		httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc, false);
		RestUtils.configureGrizzlyThreads(this.httpServer, Role.JOB_HISTORY, false);
		RestUtils.configureGrizzlyRequestLog(this.httpServer, Role.JOB_HISTORY, LogType.API);
		httpServer.start();

		// Starting the Job History Admin Server
		this.jobHistoryAdminServer = RestUtils.startAdminServer(jobHistoryResource, hibernate, Role.JOB_HISTORY, config,
				authService, this.serviceLocator);
		System.out.println("Admin server started");
		this.jobHistoryAdminServer.start();

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
//		logger.info("received a job event: " + e.getResourceType() + " " + e.getType());
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
	 * @param jobState 
	 * 
	 */

	private void handleDbEvent(SessionEvent e, IdPair jobIdPair, JobState jobState) throws RestException {
		switch (e.getType()) {
		case CREATE:
			Job job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
			
			// use the state from the event, because the state in db can be already something else
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
								
				JobHistory js = hibernateSession.get(JobHistory.class, new JobIdPair(sessionId, jobId));
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
						js.setComp(job.getComp());
						
						if (js.getStateDetail().length() > 255) {
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
		RestUtils.shutdown("JobHistory-service", httpServer);
		hibernate.getSessionFactory().close();
	}
}
