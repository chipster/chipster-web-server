package fi.csc.chipster.filestorage;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AllowSymLinkAliasChecker;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.rest.exception.ExceptionServletFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;


public class FileStorage {
		
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

	private StorageBackup backup;

	public FileStorage(Config config) {
		this.config = config;
	}

    /**
     * Starts a HTTP server exposing the REST resources defined in this application.
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
		this.serviceLocator.setCredentials(authService.getCredentials());
    	
		File storage = new File("storage");
		storage.mkdir();
		
		backup = new StorageBackup(storage.toPath(), true, config, storageId);

    	URI baseUri = URI.create(this.config.getBindUrl(Role.FILE_STORAGE));
                
    	server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(baseUri.getPort());
        connector.setHost(baseUri.getHost());
        
        connector.addBean(new HttpChannel.Listener() {

            public void onDispatchFailure(Request request, Throwable failure) {
            	logger.info("onDispatchFailure " + request.getMethod() + " " + request.getRequestURI() + "\n" + ExceptionUtils.getStackTrace(failure));
            }

            public void onRequestFailure(Request request, Throwable failure) {
            	logger.info("onRequestFailure " + request.getMethod() + " " + request.getRequestURI() + "\n" + ExceptionUtils.getStackTrace(failure));
            }

            public void onResponseFailure(Request request, Throwable failure) {            	
            	if (logger.isDebugEnabled()) {
            		logger.debug("onResponseFailure " + request.getMethod() + " " + request.getRequestURI() + "\n" + ExceptionUtils.getStackTrace(failure));
            	} else {
            		// produce concise log line without stack trace.
            		// there seems to be three separate exceptions now
            		String msg = "request cancelled " + request.getMethod() + " " + request.getRequestURI() + "\n" + failure.getClass().getSimpleName();
            		if (failure.getMessage() != null) {
            			msg += " " + failure.getMessage();
            		}
            		if (failure.getCause() != null) {
            			msg += " caused by " + failure.getCause();
            			if (failure.getCause().getMessage() != null) {
                			msg += " " + failure.getCause().getMessage();
                		}
            		}
                	logger.info(msg);            		
            	}
            }
        });
        server.addConnector(connector);
		                
		ServletContextHandler contextHandler = new ServletContextHandler(server, "/", false, false);
		// file-root and some public files are symlinks
		contextHandler.addAliasCheck(new AllowSymLinkAliasChecker());
		contextHandler.setResourceBase(storage.getPath());
				
		FileServlet fileServlet = new FileServlet(storage, authService, config);
		contextHandler.addServlet(new ServletHolder(fileServlet), "/*");
		contextHandler.addFilter(new FilterHolder(new ExceptionServletFilter()), "/*", null);
		
		CustomRequestLog requestLog = new CustomRequestLog("logs/yyyy_mm_dd.request.log", "%t %{client}a %{x-forwarded-for}i \"%r\" %k %X %s %{ms}T ms %{CLF}I B %{CLF}O B %{connection}i %{connection}o");
		server.setRequestLog(requestLog);
        
        stats = RestUtils.createStatisticsListener(server);
		
        /*
         *  Listen for file deletions here in each file-storage. If the file-brokers would listen for these
         *  events, there might be many file-broker replicas, and all those would try to delete the file
         *  from the file-storage at the same time.
         */
        sessionDbClient.subscribe(SessionDbTopicConfig.FILES_TOPIC, fileServlet, "file-storage-file-listener");
		
        server.start();                      
               
        FileStorageAdminResource adminResource = new FileStorageAdminResource(stats, backup, sessionDbClient, storage, storageId);
    	adminResource.addFileSystem("storage", storage);
		this.adminServer = RestUtils.startAdminServer(adminResource, null, Role.FILE_STORAGE, config, authService, this.serviceLocator);
    }

	/**
     * Main method.
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
		RestUtils.shutdown("file-storage-admin", adminServer);
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
			logger.warn("failed to stop the file-storage", e);
		}
	}
}

