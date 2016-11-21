package fi.csc.chipster.sessiondb;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.websocket.DeploymentException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.rest.websocket.PubSubEndpoint;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.rest.websocket.PubSubServer.TopicCheck;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.model.Authorization;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.MetadataEntry;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.resource.AuthorizationResource;
import fi.csc.chipster.sessiondb.resource.SessionDbAdminResource;
import fi.csc.chipster.sessiondb.resource.SessionResource;

/**
 * Main class.
 *
 */
public class SessionDb implements TopicCheck {

	private Logger logger = LogManager.getLogger();
	
	public static final String EVENTS_PATH = "events";
	public static final String JOBS_TOPIC = "jobs";
	public static final String FILES_TOPIC = "files";
	public static final String AUTHORIZATIONS_TOPIC = "authorizations";
	public static final String DATASETS_TOPIC = "datasets";
	public static final String SESSIONS_TOPIC = "sessions";

	private static HibernateUtil hibernate;

	@SuppressWarnings("unused")
	private String serviceId;

	private ServiceLocatorClient serviceLocator;
	private AuthenticationClient authService;

	private Config config;

	private HttpServer httpServer;
	private PubSubServer pubSubServer;

	private SessionResource sessionResource;

	private AuthorizationResource authorizationResource;

	private SessionDbAdminResource adminResource;

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
	 * @throws RestException 
	 * @throws IOException 
	 */
	public void startServer() throws ServletException, DeploymentException, RestException, IOException {

		String username = Config.USERNAME_SESSION_DB;
		String password = config.getPassword(username);

		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);

		List<Class<?>> hibernateClasses = Arrays.asList(
				Authorization.class, 
				Session.class, 
				Dataset.class, 
				MetadataEntry.class,
				Job.class, 
				Parameter.class,
				Input.class, 
				File.class); 

		boolean replicate = config.getBoolean("session-db-replicate");
		String hibernateSchema = config.getString("session-db-hibernate-schema");
		
		if (replicate) {
			// replication makes sense only with an empty DB
			hibernateSchema = "create";
		}
		
		// init Hibernate
		hibernate = new HibernateUtil(hibernateSchema);
		hibernate.buildSessionFactory(hibernateClasses, config.getString("session-db-name"));

		this.authorizationResource = new AuthorizationResource(hibernate);
		this.sessionResource = new SessionResource(hibernate, authorizationResource);
		this.adminResource = new SessionDbAdminResource(hibernate);
		
		if (replicate) {
			new SessionDbCluster().replicate(serviceLocator, authService, authorizationResource, sessionResource, adminResource, hibernate, this);
		}
				
		String pubSubUri = config.getString("session-db-events-bind");
		String path = EVENTS_PATH + "/{" + PubSubEndpoint.TOPIC_KEY + "}";

		this.pubSubServer = new PubSubServer(pubSubUri, path, authService, null, this, "session-db-events");
		this.pubSubServer.start();
		
		sessionResource.setPubSubServer(pubSubServer);

		final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
				.register(authorizationResource)
				.register(sessionResource)
				.register(adminResource)
				.register(new HibernateRequestFilter(hibernate))
				.register(new HibernateResponseFilter(hibernate))
				//.register(new LoggingFilter())
				.register(new TokenRequestFilter(authService));

		// create and start a new instance of grizzly http server
		// exposing the Jersey application at BASE_URI
		URI baseUri = URI.create(this.config.getString("session-db-bind"));
		httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
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
	 * @throws RestException 
	 */
	public static void main(String[] args) throws IOException, ServletException, DeploymentException, RestException {

		final SessionDb server = new SessionDb(new Config());
		server.startServer();
		
		RestUtils.waitForShutdown("session-db", server.getHttpServer());

		hibernate.getSessionFactory().close();
	}

	public static HibernateUtil getHibernate() {
		return hibernate;
	}

	public void close() {
		getPubSubServer().stop();		
		RestUtils.shutdown("session-db", httpServer);
	}
	
	public HttpServer getHttpServer() {
		return httpServer;
	}

	@Override
	public boolean isAuthorized(final AuthPrincipal principal, String topic) {
		logger.debug("check topic authorization for topic " + topic);
		
		if (JOBS_TOPIC.equals(topic) || FILES_TOPIC.equals(topic)) {
			return principal.getRoles().contains(Role.SERVER);
			
		} else if (DATASETS_TOPIC.equals(topic) || AUTHORIZATIONS_TOPIC.equals(topic) || SESSIONS_TOPIC.equals(topic)) {
			return principal.getRoles().contains(Role.SESSION_DB);
			
		} else {
			final UUID sessionId = UUID.fromString(topic);
			Boolean isAuthorized = hibernate.runInTransaction(new HibernateRunnable<Boolean>() {
				@Override
				public Boolean run(org.hibernate.Session hibernateSession) {
					try {
						Authorization auth = sessionResource.getAuthorizationResource().checkAuthorization(principal.getName(), sessionId, false, hibernateSession);
						return auth != null;
					} catch (fi.csc.chipster.rest.exception.NotAuthorizedException
							|javax.ws.rs.NotFoundException
							|javax.ws.rs.ForbiddenException e) {
						return false;
					}		
				}
			});
			return isAuthorized;
		} 
	}
}
