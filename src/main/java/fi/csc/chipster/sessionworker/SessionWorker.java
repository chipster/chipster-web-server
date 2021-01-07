package fi.csc.chipster.sessionworker;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.CORSServletFilter;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.rest.exception.ExceptionServletFilter;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;

/**
 * session-worker for server-side session management jobs
 * 
 * The session-worker is a service for all kind of session management jobs  
 * that need to be run on the server. Some of these jobs may potentially be quite long
 * running, like the download and upload of the session files.
 * 
 * In the strive to keep session-db as simple simple as possible, which isn't very
 * simple anyway, this is the place for all other session-related functionality.
 * 
 * At the moment there are two API endpoints. There is {@link SupportResource} for 
 * handling the support requests and {@link ZipSessionServlet} handling the 
 * download and upload of the zip sessions. The first one is 
 * a Jersey/JAX-RS resource but the latter had to be
 * implemented as a servlet for the reasons explained in {@link ZipSessionServlet}.
 *  
 * Normally we would deploy Jersey resources to a Grizzly web server, but here we
 * need a support for servlets too. Luckily it seems to be possible to deploy both
 * Jersey and servlets to Jetty. 
 * 
 * These two endpoints aren't really related, so in a true microservice architecture
 * these would probably separate services. Let's think about that when the resource
 * overheads of a service are negligible.
 */
public class SessionWorker {

	private static final String PATH_SPEC_SESSIONS = "/sessions/*";

	private Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private String serviceId;
	private Config config;

	private ServiceLocatorClient serviceLocator;
	private AuthenticationClient authService;
	private SessionDbClient sessionDb;
	
	private Server httpServer;
	private HttpServer adminServer;
	
	private SupportResource supportResource;

	public SessionWorker(Config config) {
		this.config = config;
	}

	/**
	 * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
	 * application.
	 * 
	 * @return Grizzly HTTP server.
	 * @throws Exception 
	 */
	public void startServer() throws Exception {

		String username = Role.SESSION_WORKER;
		String password = config.getPassword(username);    	
    	
    	this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());
		this.sessionDb = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.SERVER);
		
		TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
		// allow access with SessionDbTokens
		tokenRequestFilter.addAllowedRole(Role.SESSION_DB_TOKEN);
		
		this.supportResource = new SupportResource(config, authService, sessionDb);
		
		final ResourceConfig rc = RestUtils.getDefaultResourceConfig(this.serviceLocator)
				.register(supportResource)
				.register(tokenRequestFilter);
		
		// create and start a new instance of grizzly http server
		// exposing the Jersey application at BASE_URI
		URI baseUri = URI.create(this.config.getBindUrl(Role.SESSION_WORKER));
			        
        ServletContextHandler servletHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);        
        servletHandler.setContextPath("/");

        ServletHolder jerseyHolder = new ServletHolder(new ServletContainer(rc));

        servletHandler.addServlet(jerseyHolder, "/*");
        servletHandler.addServlet(new ServletHolder(new ZipSessionServlet(this.serviceLocator, this.authService)), PATH_SPEC_SESSIONS);
        servletHandler.addFilter(new FilterHolder(new ExceptionServletFilter()), PATH_SPEC_SESSIONS, null);
        servletHandler.addFilter(new FilterHolder(new CORSServletFilter(serviceLocator)), PATH_SPEC_SESSIONS, null);
        
        httpServer = new Server();
        RestUtils.configureJettyThreads(httpServer, Role.SESSION_WORKER);
        
        ServerConnector connector = new ServerConnector(httpServer);
        connector.setPort(baseUri.getPort());
        connector.setHost(baseUri.getHost());
        httpServer.addConnector(connector);
        
        httpServer.setHandler(servletHandler);
        
        StatusSource stats = RestUtils.createStatisticsListener(httpServer);
                      		
		httpServer.start();	
		adminServer = RestUtils.startAdminServer(Role.SESSION_WORKER, config, authService, this.serviceLocator, stats);
		
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
	 * @throws Exception 
	 */
	public static void main(String[] args) throws Exception {
		
		SessionWorker sessionWorker = new SessionWorker(new Config());
    	try {
    		sessionWorker.startServer();
    	} catch (Exception e) {
    		System.err.println("session-worker startup failed, exiting");
    		e.printStackTrace(System.err);
    		sessionWorker.close();
    		System.exit(1);
    	}
    	RestUtils.shutdownGracefullyOnInterrupt(sessionWorker.getHttpServer(), 
    			sessionWorker.config.getInt(Config.KEY_SESSION_WORKER_SHUTDOWN_TIMEOUT),
    			"session-worker");	
	}

	public void close() {
		RestUtils.shutdown("session-worker-admin", adminServer);
		try {
			httpServer.stop();
		} catch (Exception e) {
			logger.warn("failed to stop the session-worker", e);
		}
	}
	
	public Server getHttpServer() {
		return httpServer;
	}
}
