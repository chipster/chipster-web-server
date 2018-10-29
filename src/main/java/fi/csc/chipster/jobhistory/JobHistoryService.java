package fi.csc.chipster.jobhistory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import javax.websocket.MessageHandler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.hibernate.Session;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
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
	 */

	public void startServer() throws URISyntaxException, IOException,
			RestException {
		// WebSocket connection to Session DB
		String username = Role.JOB_HISTORY;
		String password = this.config.getPassword(username);

		this.serviceLocator = new ServiceLocatorClient(this.config);
		this.authService = new AuthenticationClient(serviceLocator, username,
				password);
		this.tokenRequestFilter = new TokenRequestFilter(authService);

		this.sessionDbClient = new SessionDbClient(serviceLocator,
				authService.getCredentials(), Role.SERVER);
		this.sessionDbClient.subscribe(SessionDbTopicConfig.JOBS_TOPIC, this,
				"job-history");

		List<Class<?>> hibernateClasses = Arrays.asList(JobHistoryModel.class);
		// Initializing hibernate components
		hibernate = new HibernateUtil(this.config, Role.JOB_HISTORY, hibernateClasses);
				
		this.jobHistoryResource=new JobHistoryResource(hibernate, config);
		
		final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
				.register(new HibernateRequestFilter(hibernate))
				.register(new HibernateResponseFilter(hibernate))
				.register(tokenRequestFilter);
		
	
		URI baseUri = URI.create(this.config.getBindUrl(Role.JOB_HISTORY));
		httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
		httpServer.start();
				
		//Starting the Job History Admin Server
		this.jobHistoryAdminServer=RestUtils.startAdminServer(jobHistoryResource,hibernate,Role.JOB_HISTORY,config,authService);
		System.out.println("Admin server started");
		this.jobHistoryAdminServer.start();

	}

	private HttpServer getHttpServer() {
		return httpServer;
	}

	public static void main(String[] args) throws URISyntaxException,
			IOException, RestException {
		final JobHistoryService jobHistoryService = new JobHistoryService(
				new Config());
		jobHistoryService.startServer();
		RestUtils.shutdownGracefullyOnInterrupt(jobHistoryService.httpServer,
				"job-history");
		RestUtils.waitForShutdown("Job History service",
				jobHistoryService.getHttpServer());

	}

	@Override
	public void onEvent(SessionEvent e) {
		logger.info("received a job event: " + e.getResourceType() + " "
				+ e.getType());
		try {
			if (e.getResourceType() == ResourceType.JOB) {
				handleDbEvent(e,
						new IdPair(e.getSessionId(), e.getResourceId()));
			}
		} catch (Exception ex) {
			logger.error("error when handling a session event", ex);
		}

	}

	/**
	 * Handling the events received from session-db
	 * 
	 */

	private void handleDbEvent(SessionEvent e, IdPair jobIdPair)
			throws RestException {
		switch (e.getType()) {
		case CREATE:
			Job job = sessionDbClient.getJob(e.getSessionId(),
					e.getResourceId());
			System.out.println(job);
			switch (job.getState()) {
			case NEW:
				// When a client adds a new job, save it the job history
				// database
				// hibernate.getsession.save()
				saveJobHistory(job);
				break;
			case COMPLETED:
				logger.info("For imported sessions, jobs are not logged in to job history database");
			default:
				break;
			}
			break;
		case UPDATE:
			job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
			switch (job.getState()) {
			case COMPLETED:
			case FAILED:
			case RUNNING:
			case FAILED_USER_ERROR:
				updateJobHistory(job);
				break;
			default:
				break;
			}
		default:
			break;
		// what to do with if the client has cancelled the job?
		}
	}

	private void saveJobHistory(Job job) {
		JobHistoryModel jobHistory = new JobHistoryModel();
		jobHistory.setJobId(job.getJobId());
		jobHistory.setToolId(job.getToolId());
		jobHistory.setToolName(job.getToolName());
		jobHistory.setStartTime(job.getStartTime());
		jobHistory.setEndTime(job.getEndTime());
		jobHistory.setTimeDuration(Long.toString(Math.abs(Duration.between(job.getEndTime(),job.getStartTime()).getSeconds())));
		jobHistory.setOutput(job.getScreenOutput());
		jobHistory.setJobStatus(job.getState().toString());
		jobHistory.setUserName(job.getCreatedBy());
		getHibernate().runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(Session hibernateSession) {
				hibernateSession.save(jobHistory);
				return null;
			}
		});
	}

	// Should we be using merge or update??
	private void updateJobHistory(Job job) {
		getHibernate().runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(Session hibernateSession) {
				JobHistoryModel js = hibernateSession.get(
						JobHistoryModel.class, job.getJobId());
				js.setToolId(job.getToolId());
				js.setToolName(job.getToolName());
				js.setStartTime(job.getStartTime());
				js.setEndTime(job.getEndTime());
				js.setTimeDuration(Long.toString(Math.abs(Duration.between(job.getEndTime(),job.getStartTime()).getSeconds())));
				js.setOutput(job.getScreenOutput());
				js.setJobStatus(job.getState().toString());
				js.setUserName(job.getCreatedBy());
				
				HibernateUtil.update(js, js.getJobId(), hibernateSession);

				return null;
			}
		});
	}

	private HibernateUtil getHibernate() {
		return hibernate;
	}

	public void close() {
		RestUtils.shutdown("JobHistory-service", httpServer);
	}
	

}
