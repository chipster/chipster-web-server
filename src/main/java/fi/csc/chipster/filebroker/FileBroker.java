package fi.csc.chipster.filebroker;

import java.io.File;
import java.io.IOException;
import java.net.URI;

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
		this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.CLIENT);		
    	
		File storage = new File("storage");
		storage.mkdir();

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

