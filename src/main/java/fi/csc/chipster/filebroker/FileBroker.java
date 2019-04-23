package fi.csc.chipster.filebroker;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AllowSymLinkAliasChecker;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.CORSServletFilter;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.rest.exception.ExceptionServletFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;


public class FileBroker {
		
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

	@SuppressWarnings("unused")
	private StorageBackup backup;

	public FileBroker(Config config) {
		this.config = config;
	}

    /**
     * Starts a HTTP server exposing the REST resources defined in this application.
     * @return 
     * @throws Exception  
     */
    public void startServer() throws Exception {
    	
    	String username = Role.FILE_BROKER;
    	String password = config.getPassword(username);    	
    	
    	this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);
		this.serviceLocator.setCredentials(authService.getCredentials());
		
		this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());
    	
		File storage = new File("storage");
		storage.mkdir();
		
		convertStorage(storage);
		
		backup = new StorageBackup(storage.toPath(), true, config);

    	URI baseUri = URI.create(this.config.getBindUrl(Role.FILE_BROKER));
                
    	server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(baseUri.getPort());
        connector.setHost(baseUri.getHost());
        server.addConnector(connector);
		                
		ServletContextHandler contextHandler = new ServletContextHandler(server, "/", false, false);
		// file-root and some public files are symlinks
		contextHandler.addAliasCheck(new AllowSymLinkAliasChecker());
		contextHandler.setResourceBase(storage.getPath());
				
		FileServlet fileServlet = new FileServlet(storage, sessionDbClient, serviceLocator, authService);
		contextHandler.addServlet(new ServletHolder(fileServlet), "/*");
		contextHandler.addFilter(new FilterHolder(new ExceptionServletFilter()), "/*", null);
		contextHandler.addFilter(new FilterHolder(new CORSServletFilter()), "/*", null);
        
        stats = RestUtils.createStatisticsListener(server);
		
        sessionDbClient.subscribe(SessionDbTopicConfig.FILES_TOPIC, fileServlet, "file-broker-file-listener");
		
        server.start();                      
               
        AdminResource adminResource = new AdminResource(stats);
    	adminResource.addFileSystem("storage", storage);
		this.adminServer = RestUtils.startAdminServer(adminResource, null, Role.FILE_BROKER, config, authService);
        
    }

    /**
     * Original file-broker stored all files directly in the storage directory.
     * Now first characters of the UUID form so called partition, each of them
     * having its own subfolder. Move files to correct partition subfolders.
     * 
     * UUID is presented in hex, so each character is 4 bits. Two characters
     * is 8 bits i.e. 256 partitions.
     * 
     * @param storageFile
     * @throws IOException
     */
    private void convertStorage(File storageFile) throws IOException {    	
    	Path storage = storageFile.toPath();    	
    	
    	// a supplier is needed to use the same stream twice
    	Supplier<Stream<UUID>> streamSupplier = () -> {
			try {
				return Files.list(storage)
					.filter(path -> Files.isRegularFile(path))
					.map(path -> path.getFileName().toString())
					.map(fileName -> {
						try {
							return UUID.fromString(fileName);
						} catch (IllegalArgumentException e) {
							logger.error("non-UUID file in storage: " + fileName);
							return null;
						}
					})
					.filter(fileId -> fileId != null);
			} catch (IOException e) {
				throw new RuntimeException("failed to list the storage files", e);
			}
		};
		
    	streamSupplier.get().findAny().ifPresent(fileId -> logger.info("converting storage layout"));
		
    	streamSupplier.get().forEach(fileId -> {
				Path src = storage.resolve(fileId.toString());
				Path dest = FileServlet.getStoragePath(storage, fileId);				
				try {
					Files.move(src, dest);
				} catch (IOException e) {
					logger.error("failed to move file from " + src + " to " + dest, e);
				}
			});			
	}

	/**
     * Main method.
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
    			fileBroker.server, 
    			fileBroker.config.getInt(Config.KEY_FILE_BROKER_SHUTDOWN_TIMEOUT), 
    			"file-broker");	
    }

	public void close() {
		RestUtils.shutdown("file-broker-admin", adminServer);
		try {
			try {
				if (sessionDbClient != null) {
					sessionDbClient.close();
				}
			} catch (IOException e) {
				logger.warn("failed to shutdown session-db client", e);
			}
			server.stop();
		} catch (Exception e) {
			logger.warn("failed to stop the file broker", e);
		}
	}
}

