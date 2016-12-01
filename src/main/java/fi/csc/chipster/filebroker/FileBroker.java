package fi.csc.chipster.filebroker;

import java.io.File;
import java.net.URI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AllowSymLinkAliasChecker;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.CORSServletFilter;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.exception.ExceptionServletFilter;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.rest.token.TokenServletFilter;
import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketClosedException;
import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketErrorException;
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

	public FileBroker(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     * @throws Exception 
     * @throws InterruptedException 
     * @throws WebSocketClosedException 
     * @throws WebSocketErrorException 
     */
    public void startServer() throws Exception {
    	
    	String username = Config.USERNAME_FILE_BROKER;
    	String password = config.getPassword(username);    	
    	
    	this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);
		this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials());		
    	
		File storage = new File("storage");
		storage.mkdir();    
		//this.fileResource = new FileResource(storage, sessionDbClient);
		
    	
    	TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
//    	        
//    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
//        	.register(fileResource)
//        	//.register(new LoggingFilter())
//        	.register(tokenRequestFilter);
//
//        // create and start a new instance of grizzly http server
//        // exposing the Jersey application at BASE_URI
    	URI baseUri = URI.create(this.config.getString("file-broker-bind"));
//        this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
    	
//    	ResourceConfig config = new ResourceConfig(FileResource.class);
//    	server = JettyHttpContainerFactory.createServer(baseUri, config);

                
    	server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(baseUri.getPort());
        connector.setHost(baseUri.getHost());
        server.addConnector(connector);
		
		ServletContextHandler contextHandler = new ServletContextHandler(server, "/", false, false);
		// file-root and some public files are symlinks
		contextHandler.addAliasCheck(new AllowSymLinkAliasChecker());
		contextHandler.setResourceBase(storage.getPath());
				
		FileServlet fileServlet = new FileServlet(storage, sessionDbClient);
		contextHandler.addServlet(new ServletHolder(fileServlet), "/*");
		contextHandler.addFilter(new FilterHolder(new ExceptionServletFilter()), "/*", null);
		contextHandler.addFilter(new FilterHolder(new TokenServletFilter(tokenRequestFilter)), "/*", null);
		contextHandler.addFilter(new FilterHolder(new CORSServletFilter()), "/*", null);
		
		sessionDbClient.subscribe(SessionDb.FILES_TOPIC, fileServlet, "file-broker-file-listener");
               
        server.start();
        //server.join();
    }

    /**
     * Main method.
     * @param args
     * @throws Exception 
     * @throws InterruptedException 
     */
    public static void main(String[] args) throws Exception {
    	
//        final FileBroker server = new FileBroker(new Config());
//        server.startServer();
//        RestUtils.waitForShutdown("file-broker", server.getHttpServer());
        
        new FileBroker(new Config()).startServer();
    }

//	private HttpServer getHttpServer() {
//		return this.httpServer;
//	}

	public void close() {
		//RestUtils.shutdown("file-broker", httpServer);
		try {
			server.stop();
		} catch (Exception e) {
			logger.warn("failed to stop the file brokers", e);
		}
	}
}

