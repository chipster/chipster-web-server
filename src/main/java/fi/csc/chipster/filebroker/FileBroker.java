package fi.csc.chipster.filebroker;

import java.net.URI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filestorage.client.FileStorageDiscovery;
import fi.csc.chipster.rest.CORSServletFilter;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServerComponent;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.rest.exception.ExceptionServletFilter;
import fi.csc.chipster.s3storage.client.S3StorageClient;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.SessionDbAdminClient;
import fi.csc.chipster.sessiondb.SessionDbClient;

public class FileBroker implements ServerComponent {

	private static final String CONF_KEY_FILE_BROKER_CHUNKED_ENCONDING = "file-broker-chunked-encoding";

	private Logger logger = LogManager.getLogger();

	private AuthenticationClient authService;
	private ServiceLocatorClient serviceLocator;
	private Config config;
	@SuppressWarnings("unused")
	private String serviceId;

	private SessionDbClient sessionDbClient;

	private HttpServer adminServer;

	private Server httpServer;

	private FileStorageDiscovery storageDiscovery;

	private S3StorageClient s3StorageClient;

	private SessionDbAdminClient sessionDbAdminClient;

	private FileBrokerApi fileBrokerApi;

	public FileBroker(Config config) {
		this.config = config;
	}

	/**
	 * Starts a HTTP server exposing the REST resources defined in this application.
	 * 
	 * @return
	 * @throws Exception
	 */
	public void startServer() throws Exception {

		String username = Role.FILE_BROKER;
		String password = config.getPassword(username);

		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());

		this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.SERVER);
		this.sessionDbAdminClient = new SessionDbAdminClient(serviceLocator, authService.getCredentials());
		this.s3StorageClient = new S3StorageClient(config, Role.FILE_BROKER);

		this.storageDiscovery = new FileStorageDiscovery(this.serviceLocator, authService, config);
		this.fileBrokerApi = new FileBrokerApi(this.s3StorageClient, this.storageDiscovery, this.sessionDbAdminClient,
				this.sessionDbClient, this.serviceLocator);

		// FileBrokerResourceServlet is implemented as servlet to be able report errors
		// to browser
		ServletContextHandler servletHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
		servletHandler.setContextPath("/");

		URI baseUri = URI.create(this.config.getBindUrl(Role.FILE_BROKER));

		boolean useChunkedEncoding = this.config.getBoolean(CONF_KEY_FILE_BROKER_CHUNKED_ENCONDING);

		servletHandler.addServlet(
				new ServletHolder(new FileBrokerResourceServlet(this.fileBrokerApi, useChunkedEncoding)),
				"/*");
		servletHandler.addFilter(new FilterHolder(new ExceptionServletFilter()),
				"/*", null);
		servletHandler.addFilter(new FilterHolder(new CORSServletFilter(serviceLocator)), "/*", null);

		httpServer = new Server();
		RestUtils.configureJettyThreads(httpServer, Role.FILE_BROKER, config);

		ServerConnector connector = new ServerConnector(httpServer);
		connector.setPort(baseUri.getPort());
		connector.setHost(baseUri.getHost());
		httpServer.addConnector(connector);

		httpServer.setHandler(servletHandler);

		StatusSource stats = RestUtils.createStatisticsListener(httpServer);

		httpServer.start();

		FileBrokerAdminResource adminResource = new FileBrokerAdminResource(stats, storageDiscovery,
				sessionDbAdminClient, s3StorageClient, fileBrokerApi, config);

		this.adminServer = RestUtils.startAdminServer(adminResource, null, Role.FILE_BROKER, config, authService,
				this.serviceLocator);
	}

	/**
	 * Main method.
	 * 
	 * @param args
	 * @throws Exception
	 * @throws InterruptedException s
	 */
	public static void main(String[] args) throws Exception {
		FileBroker fileBroker = new FileBroker(new Config());
		try {
			fileBroker.startServer();
		} catch (Exception e) {
			System.err.println("file-broker startup failed, exiting");
			e.printStackTrace(System.err);
			fileBroker.close();
			System.exit(1);
		}
		RestUtils.shutdownGracefullyOnInterrupt(
				fileBroker.httpServer,
				fileBroker.config.getInt(Config.KEY_FILE_BROKER_SHUTDOWN_TIMEOUT),
				"file-broker");
	}

	public void close() {
		RestUtils.shutdown("file-broker-admin", adminServer);
		try {
			httpServer.stop();
			authService.close();
		} catch (Exception e) {
			logger.warn("failed to stop the file-broker", e);
		}
	}
}
