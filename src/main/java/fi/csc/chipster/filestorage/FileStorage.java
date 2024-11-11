package fi.csc.chipster.filestorage;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServerComponent;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.rest.exception.ExceptionServletFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.SessionDbAdminClient;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;

/**
 * file-storage component
 * 
 * Block devices are easy to implement efficiently (in comparison the shared
 * file systems), but difficult to scale in size and throughput. file-broker and
 * file-storage together form a scalable object storage system to overcome both
 * problems.
 * 
 * file-storage stores files in a directory. In a Kubernetes, this
 * directory is usually a mounted ReadWriteOnce volume. By creating a
 * StatefulSet, we can create a group of file-storages, each having its own
 * volume. This allows us to add more storage space simply by adding more
 * file-storage replicas.
 * 
 * However, this creates a new problem, because now the other components would
 * have to know where to find each file. This is solved by using file-brokers as
 * stateless reverse proxies, that forward the requests to the correct
 * file-storage instance. Because file-brokers are stateless, clients can access
 * any file from any file-broker instance and more file-brokers can be added, if
 * those become a bottleneck.
 * 
 * file-storage listens WebSocket File events from session-db to delete the
 * real file from the file-system directory when a respective File object
 * is deleted from the database.
 */
public class FileStorage implements ServerComponent {

	private Logger logger = LogManager.getLogger();

	private AuthenticationClient authService;
	private ServiceLocatorClient serviceLocator;
	private Config config;
	@SuppressWarnings("unused")
	private String serviceId;

	private SessionDbClient sessionDbClient;

	private Server server;

	private HttpServer adminServer;

	private StatusSource stats;

	private FileStorageBackup backup;

	private SessionDbAdminClient sessionDbAdminClient;

	public FileStorage(Config config) {
		this.config = config;
	}

	/**
	 * Starts a HTTP server exposing the REST resources defined in this application.
	 * 
	 * @return
	 * @throws Exception
	 */
	public void startServer() throws Exception {

		String username = Role.FILE_STORAGE;
		String password = config.getPassword(username);

		String storageId = config.getString("file-storage-id");
		if (storageId == null || storageId.isEmpty()) {
			storageId = InetAddress.getLocalHost().getHostName();
		}

		logger.info("file-storage storageId '" + storageId + "'");

		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());

		this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.SERVER);
		this.sessionDbAdminClient = new SessionDbAdminClient(serviceLocator, authService.getCredentials());

		this.serviceLocator.setCredentials(authService.getCredentials());

		File storage = new File("storage");
		storage.mkdir();

		// resolve symlinks before giving it to Jetty, because AliasCheck fails if the
		// served file is outside of the base path
		if (Files.isSymbolicLink(storage.toPath())) {
			storage = storage.toPath().toRealPath().toFile();
			logger.info("resolved symlink 'storage' to " + storage);
		}

		backup = new FileStorageBackup(storage.toPath(), true, config, storageId);

		URI baseUri = URI.create(this.config.getBindUrl(Role.FILE_STORAGE));

		server = new Server();
		RestUtils.configureJettyThreads(server, Role.FILE_STORAGE, config);

		ServerConnector connector = new ServerConnector(server);
		connector.setPort(baseUri.getPort());
		connector.setHost(baseUri.getHost());

		server.addConnector(connector);

		ServletContextHandler contextHandler = new ServletContextHandler("/", false, false);

		contextHandler.setBaseResourceAsPath(storage.toPath().toRealPath());

		FileServlet fileServlet = new FileServlet(storage, authService, config);
		contextHandler.addServlet(new ServletHolder(fileServlet), "/*");
		contextHandler.addFilter(new FilterHolder(new ExceptionServletFilter()), "/*", null);

		server.setHandler(contextHandler);

		CustomRequestLog requestLog = new CustomRequestLog("logs/yyyy_mm_dd.request.log",
				"%t %{client}a %{x-forwarded-for}i \"%r\" %k %X %s %{ms}T ms %{CLF}I B %{CLF}O B %{connection}i %{connection}o");
		server.setRequestLog(requestLog);

		stats = RestUtils.createStatisticsListener(server);

		/*
		 * Listen for file deletions here in each file-storage. If the file-brokers
		 * would listen for these
		 * events, there might be many file-broker replicas, and all those would try to
		 * delete the file
		 * from the file-storage at the same time.
		 */
		sessionDbClient.subscribe(SessionDbTopicConfig.ALL_FILES_TOPIC, fileServlet, "file-storage-file-listener");

		server.start();

		FileStorageAdminResource adminResource = new FileStorageAdminResource(stats, backup, sessionDbAdminClient,
				storage,
				storageId);
		adminResource.addFileSystem("storage", storage);
		this.adminServer = RestUtils.startAdminServer(adminResource, null, Role.FILE_STORAGE, config, authService,
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
		FileStorage fileBroker = new FileStorage(new Config());
		try {
			fileBroker.startServer();
		} catch (Exception e) {
			System.err.println("file-storage startup failed, exiting");
			e.printStackTrace(System.err);
			fileBroker.close();
			System.exit(1);
		}
		RestUtils.shutdownGracefullyOnInterrupt(
				fileBroker.server,
				fileBroker.config.getInt(Config.KEY_FILE_BROKER_SHUTDOWN_TIMEOUT),
				"file-storage");
	}

	public void close() {
		try {
			try {
				if (sessionDbClient != null) {
					// shutdown websocket first (see ServerLauncher.stop())
					sessionDbClient.close();
				}
				authService.close();
			} catch (IOException e) {
				logger.warn("failed to shutdown session-db client", e);
			}
			server.stop();
		} catch (Exception e) {
			logger.warn("failed to stop the file-storage", e);
		}
		RestUtils.shutdown("file-storage-admin", adminServer);
	}
}
