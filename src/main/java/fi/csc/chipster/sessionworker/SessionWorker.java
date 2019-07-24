package fi.csc.chipster.sessionworker;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.ServletException;
import javax.websocket.DeploymentException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.JerseyStatisticsSource;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;

/**
 * Main class.
 *
 */
public class SessionWorker {

	private Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private String serviceId;
	private Config config;

	private ServiceLocatorClient serviceLocator;
	private AuthenticationClient authService;
	private SessionDbClient sessionDb;
	
	private HttpServer httpServer;
	private HttpServer adminServer;
	
	private SessionWorkerResource sessionWorkerResource;
	private SupportResource supportResource;

	public SessionWorker(Config config) {
		this.config = config;
	}

	/**
	 * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
	 * application.
	 * 
	 * @return Grizzly HTTP server.
	 * @throws DeploymentException 
	 * @throws ServletException 
	 * @throws RestException 
	 * @throws IOException 
	 */
	public void startServer() throws ServletException, DeploymentException, RestException, IOException {

		String username = Role.SESSION_WORKER;
		String password = config.getPassword(username);    	
    	
    	this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);
		this.serviceLocator.setCredentials(authService.getCredentials());
		this.sessionDb = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.SERVER);
		
		TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
		
		this.sessionWorkerResource = new SessionWorkerResource(serviceLocator);
		this.supportResource = new SupportResource(config, authService, sessionDb);
		
		final ResourceConfig rc = RestUtils.getDefaultResourceConfig(this.serviceLocator)
				.register(sessionWorkerResource)
				.register(supportResource)
				.register(tokenRequestFilter);
		
    	JerseyStatisticsSource jerseyStatisticsSource = RestUtils.createJerseyStatisticsSource(rc);		

		// create and start a new instance of grizzly http server
		// exposing the Jersey application at BASE_URI
		URI baseUri = URI.create(this.config.getBindUrl(Role.SESSION_WORKER));
		httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc, false);
		
		jerseyStatisticsSource.collectConnectionStatistics(httpServer);
		
		httpServer.start();
		
		adminServer = RestUtils.startAdminServer(Role.SESSION_WORKER, config, authService, this.serviceLocator, jerseyStatisticsSource);
		
		// clean up daily
		long cleanUpInterval = 24l * 60 * 60 * 1000;
		
		// start after random time between 0 and cleanUpInterval to make conflicts 
		// between session-worker replicas less likely
		long cleanUpStart = Math.abs(new Random().nextLong()) % cleanUpInterval;
		
		new Timer(true).schedule(new SupportSessionCleanUp(), cleanUpStart, cleanUpInterval);
	}
	
	class SupportSessionCleanUp extends TimerTask {
		@Override
		public void run() {
			try {
				logger.info("support session clean-up started");
				String owner = config.getString(Config.KEY_SESSION_WORKER_SUPPORT_SESSION_OWNER);
				int deleteAfterDays = config.getInt(Config.KEY_SESSION_WORKER_SUPPORT_SESSION_DELETE_AFTER);
				List<Session> sessions = sessionDb.getSessions(owner);
				
				for (Session session : sessions) {
					try {
						Rule rule = session.getRules().stream()
							.filter(r -> owner.equals(r.getUsername()))
							.findAny().get();					
						
						if (rule.getCreated().isBefore(Instant.now().minus(deleteAfterDays, ChronoUnit.DAYS))) {
							logger.info("delete old support session " + session.getName());
							// delete only the support_session_owner's rule and not the whole session in case someone
							// wants to keep a copy of it
							sessionDb.deleteRule(session.getSessionId(), rule.getRuleId());
						}
					} catch (NoSuchElementException e) {
						// just continue if a rule wasn't found, this is probably an example session
					}
				}
				logger.info("support session clean-up done");
			} catch (RestException e) {
				logger.error("error in support session clean-up", e);
			}
		}
	}

	/**
	 * Main method.
	 * 
	 * @param args
	 * @throws IOException
	 * @throws DeploymentException 
	 * @throws ServletException 
	 * @throws RestException 
	 */
	public static void main(String[] args) throws IOException, ServletException, DeploymentException, RestException {

		final SessionWorker service = new SessionWorker(new Config());
		
		RestUtils.shutdownGracefullyOnInterrupt(service.getHttpServer(), service.config.getInt(Config.KEY_SESSION_WORKER_SHUTDOWN_TIMEOUT), Role.SESSION_WORKER);
		
		service.startServer();
		
		RestUtils.waitForShutdown("session-worker", service.getHttpServer());
	}

	public void close() {
		RestUtils.shutdown("session-worker-admin", adminServer);
		RestUtils.shutdown("session-worker", httpServer);
	}
	
	public HttpServer getHttpServer() {
		return httpServer;
	}
}
