package fi.csc.chipster.sessiondb;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import javax.servlet.ServletException;
import javax.websocket.DeploymentException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.media.sse.SseFeature;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.scheduler.PubSubEndpoint;
import fi.csc.chipster.scheduler.PubSubServer;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.model.Authorization;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.resource.SessionResource;

/**
 * Main class.
 *
 */
public class SessionDb {

	@SuppressWarnings("unused")
	private Logger logger = LogManager.getLogger();

	private static HibernateUtil hibernate;

	@SuppressWarnings("unused")
	private String serviceId;

	private ServiceLocatorClient serviceLocator;

	private AuthenticationClient authService;

	private Config config;

	private HttpServer httpServer;

	private PubSubServer pubSubServer;

	public SessionDb(Config config) {
		this.config = config;
	}

	/**
	 * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
	 * application.
	 * 
	 * @return Grizzly HTTP server.
	 * @throws DeploymentException 
	 * @throws ServletException 
	 */
	public void startServer() throws ServletException, DeploymentException {

		String username = "sessionStorage";
		String password = "sessionStoragePassword";

		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username,
				password);
		this.serviceId = serviceLocator.register(Role.SESSION_DB, authService, config.getString("session-db"));
		serviceLocator.register(Role.SESSION_DB_EVENTS, authService, config.getString("session-db-events"));

		List<Class<?>> hibernateClasses = Arrays.asList(new Class<?>[] {
				Session.class, Dataset.class, Job.class, Parameter.class,
				Input.class, File.class, Authorization.class, });

		// init Hibernate
		hibernate = new HibernateUtil();
		hibernate.buildSessionFactory(hibernateClasses, "session-db");

		startPubSubServer();

		final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
				.register(new SessionResource(hibernate, pubSubServer))
				//.register(new AdminResource(events))
				.register(new HibernateRequestFilter(hibernate))
				.register(new HibernateResponseFilter(hibernate))
				.register(SseFeature.class)
				//.register(new LoggingFilter())
				.register(new TokenRequestFilter(authService));

		// create and start a new instance of grizzly http server
		// exposing the Jersey application at BASE_URI
		URI baseUri = URI.create(this.config.getString("session-db-bind"));
		httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
	}

	public void startPubSubServer() throws ServletException, DeploymentException {

		String pubSubUri = config.getString("session-db-events-bind");
		String path = "sessions/{" + PubSubEndpoint.TOPIC_KEY + "}/events";

		this.pubSubServer = new PubSubServer(pubSubUri, path, null, null);
		this.pubSubServer.start();
	}
	
	public PubSubServer getPubSubServer() {
		return pubSubServer;
	}

	/**
	 * Main method.
	 * 
	 * @param args
	 * @throws IOException
	 * @throws DeploymentException 
	 * @throws ServletException 
	 */
	public static void main(String[] args) throws IOException, ServletException, DeploymentException {

		final SessionDb server = new SessionDb(new Config());
		server.startServer();
		RestUtils.waitForShutdown("session-db", server.getHttpServer());

		hibernate.getSessionFactory().close();
	}

	public static HibernateUtil getHibernate() {
		return hibernate;
	}

	public void close() {
		getPubSubServer();		
		RestUtils.shutdown("session-db", httpServer);
	}
	
	public HttpServer getHttpServer() {
		return httpServer;
	}
}
