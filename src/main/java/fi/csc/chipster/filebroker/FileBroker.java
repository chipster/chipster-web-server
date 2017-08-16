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
import fi.csc.chipster.rest.CORSServletFilter;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.ExceptionServletFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.SessionDb;
import fi.csc.chipster.sessiondb.SessionDbClient;


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
		this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials());		
    	
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
				
		FileServlet fileServlet = new FileServlet(storage, sessionDbClient, serviceLocator);
		contextHandler.addServlet(new ServletHolder(fileServlet), "/*");
		contextHandler.addFilter(new FilterHolder(new ExceptionServletFilter()), "/*", null);
		contextHandler.addFilter(new FilterHolder(new CORSServletFilter()), "/*", null);
		
		sessionDbClient.subscribe(SessionDb.FILES_TOPIC, fileServlet, "file-broker-file-listener");
               
        server.start();
        
        adminServer = RestUtils.startAdminServer(Role.FILE_BROKER, config, authService, null);
    }

    /**
     * Main method.
     * @param args
     * @throws Exception 
     * @throws InterruptedException s
     */
    public static void main(String[] args) throws Exception {
    	
        new FileBroker(new Config()).startServer();
    }

	public void close() {
		RestUtils.waitForShutdown("file-broker-admin", adminServer);
		try {
			try {
				sessionDbClient.close();
			} catch (IOException e) {
				logger.warn("failed to shutdown session-db client", e);
			}
			server.stop();
		} catch (Exception e) {
			logger.warn("failed to stop the file broker", e);
		}
	}
}

