package fi.csc.chipster.sessiondb;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

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
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.rest.websocket.PubSubEndpoint;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.DatasetToken;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.MetadataEntry;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.resource.DatasetTokenResource;
import fi.csc.chipster.sessiondb.resource.DatasetTokenTable;
import fi.csc.chipster.sessiondb.resource.GlobalJobResource;
import fi.csc.chipster.sessiondb.resource.RuleTable;
import fi.csc.chipster.sessiondb.resource.SessionDbAdminResource;
import fi.csc.chipster.sessiondb.resource.SessionResource;
import fi.csc.chipster.sessiondb.resource.UserResource;

//import sun.misc.SignalHandler;
//import sun.misc.Signal;

/**
 * Main class.
 *
 */
@SuppressWarnings("unused")
public class SessionDb {

	private Logger logger = LogManager.getLogger();

	public static final String EVENTS_PATH = "events";

	private static HibernateUtil hibernate;

	private String serviceId;

	private ServiceLocatorClient serviceLocator;
	private AuthenticationClient authService;

	private Config config;

	private HttpServer httpServer;
	private PubSubServer pubSubServer;

	private SessionResource sessionResource;

	private RuleTable authorizationTable;

	private SessionDbAdminResource adminResource;

	private GlobalJobResource globalJobResource;

	private DatasetTokenResource datasetTokenResource;

	private TokenRequestFilter tokenRequestFilter;

	private HttpServer adminServer;

	private UserResource userResource;

	public SessionDb(Config config) {
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

		String username = Role.SESSION_DB;
		String password = config.getPassword(username);

		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username,
				password);

		List<Class<?>> hibernateClasses = Arrays.asList(DatasetToken.class,
				Rule.class, Session.class, Dataset.class, MetadataEntry.class,
				Job.class, Parameter.class, Input.class, File.class);

		// init Hibernate
		hibernate = new HibernateUtil(config, Role.SESSION_DB, hibernateClasses);

		this.tokenRequestFilter = new TokenRequestFilter(authService);
		// access with DatasetTokens is anonymous
		this.tokenRequestFilter.authenticationRequired(false, true);

		DatasetTokenTable datasetTokenTable = new DatasetTokenTable(hibernate);
		
		this.authorizationTable = new RuleTable(hibernate, datasetTokenTable, tokenRequestFilter);
		this.datasetTokenResource = new DatasetTokenResource(datasetTokenTable, authorizationTable);
		this.sessionResource = new SessionResource(hibernate, authorizationTable, config);
		this.globalJobResource = new GlobalJobResource(hibernate);
		this.userResource = new UserResource(hibernate);
				
		String pubSubUri = config.getBindUrl(Role.SESSION_DB_EVENTS);
		String path = EVENTS_PATH + "/{" + PubSubEndpoint.TOPIC_KEY + "}";

		SessionDbTopicConfig topicConfig = new SessionDbTopicConfig(
				authService, hibernate, sessionResource);
		this.pubSubServer = new PubSubServer(pubSubUri, path, null,
				topicConfig, "session-db-events");
		this.pubSubServer.setIdleTimeout(config
				.getLong(Config.KEY_WEBSOCKET_IDLE_TIMEOUT));
		this.pubSubServer.start();

		sessionResource.setPubSubServer(pubSubServer);

		final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
				.register(datasetTokenResource).register(authorizationTable)
				.register(sessionResource).register(globalJobResource)
				.register(userResource)
				.register(new HibernateRequestFilter(hibernate))
				.register(new HibernateResponseFilter(hibernate))
				// .register(RestUtils.getLoggingFeature("session-db"))
				.register(tokenRequestFilter);

		JerseyStatisticsSource jerseyStatisticsSource = RestUtils
				.createJerseyStatisticsSource(rc);
		this.adminResource = new SessionDbAdminResource(hibernate,
				jerseyStatisticsSource, pubSubServer);

		// create and start a new instance of grizzly http server
		// exposing the Jersey application at BASE_URI
		URI baseUri = URI.create(this.config.getBindUrl(Role.SESSION_DB));

		httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);

		jerseyStatisticsSource.collectConnectionStatistics(httpServer);

		httpServer.start();

		adminServer = RestUtils.startAdminServer(adminResource, hibernate,
				Role.SESSION_DB, config, authService);
		System.out.println("Admin server started");
	}

	public PubSubServer getPubSubServer() {
		return pubSubServer;
	}

	/**
	 * Main method.
	 * 
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {

		final SessionDb service = new SessionDb(new Config());
		service.startServer();

		RestUtils.shutdownGracefullyOnInterrupt(service.getHttpServer(),
				Role.SESSION_DB);

		RestUtils.waitForShutdown("session-db", service.getHttpServer());

		hibernate.getSessionFactory().close();
	}

	public static HibernateUtil getHibernate() {
		return hibernate;
	}

	public void close() {
		RestUtils.shutdown("session-db-admin", adminServer);
		getPubSubServer().stop();
		RestUtils.shutdown("session-db", httpServer);
	}

	public HttpServer getHttpServer() {
		return httpServer;
	}
}
