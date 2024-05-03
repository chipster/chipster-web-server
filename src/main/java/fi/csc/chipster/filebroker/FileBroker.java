package fi.csc.chipster.filebroker;

import java.net.URI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filestorage.client.FileStorageDiscovery;
import fi.csc.chipster.rest.CORSServletFilter;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.ExceptionServletFilter;
import fi.csc.chipster.s3storage.client.S3StorageAdminClient;
import fi.csc.chipster.s3storage.client.S3StorageClient;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.SessionDbAdminClient;
import fi.csc.chipster.sessiondb.SessionDbClient;

public class FileBroker {

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

	private S3StorageAdminClient s3StorageAdminClient;

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
		this.s3StorageClient = new S3StorageClient(config);
		this.s3StorageAdminClient = new S3StorageAdminClient(s3StorageClient, sessionDbAdminClient);

		this.storageDiscovery = new FileStorageDiscovery(this.serviceLocator, authService, config);
		this.fileBrokerApi = new FileBrokerApi(this.s3StorageClient, this.storageDiscovery, this.sessionDbAdminClient,
				this.sessionDbClient, this.serviceLocator);

		ServletContextHandler servletHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
		servletHandler.setContextPath("/");

		URI baseUri = URI.create(this.config.getBindUrl(Role.FILE_BROKER));

		servletHandler.addServlet(
				new ServletHolder(new FileBrokerResourceServlet(this.fileBrokerApi)),
				"/*");
		servletHandler.addFilter(new FilterHolder(new ExceptionServletFilter()),
				"/*", null);
		servletHandler.addFilter(new FilterHolder(new CORSServletFilter(serviceLocator)), "/*", null);

		FileBrokerAdminResource adminResource = new FileBrokerAdminResource(null, storageDiscovery,
				sessionDbAdminClient, s3StorageClient, s3StorageAdminClient, fileBrokerApi);

		this.adminServer = RestUtils.startAdminServer(adminResource, null, Role.FILE_BROKER, config, authService,
				this.serviceLocator);

		httpServer = new Server();
		RestUtils.configureJettyThreads(httpServer, Role.FILE_BROKER, config);

		ServerConnector connector = new ServerConnector(httpServer);
		connector.setPort(baseUri.getPort());
		connector.setHost(baseUri.getHost());
		httpServer.addConnector(connector);

		httpServer.setHandler(servletHandler);

		httpServer.start();

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
		} catch (Exception e) {
			logger.warn("failed to stop the file-broker", e);
		}
	}
}
