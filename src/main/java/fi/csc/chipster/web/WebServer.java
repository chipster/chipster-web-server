package fi.csc.chipster.web;

import java.io.File;
import java.net.URI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AllowSymLinkAliasChecker;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

public class WebServer {
	
	private static final String INDEX_HTML = "index.html";

	private static final Logger logger = LogManager.getLogger();
	
	private Config config;
	private Server server;

	private ServiceLocatorClient serviceLocator;

	private AuthenticationClient authService;

	private HttpServer adminServer;

	public WebServer(Config config) {
		this.config = config;
	}

	public void start() throws Exception {
		
    	String username = Role.WEB_SERVER;
    	String password = config.getPassword(username);    	
    	
    	this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);
		
        URI baseUri = URI.create(config.getBindUrl(Role.WEB_SERVER));
        
        server = new Server();
        
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(baseUri.getPort());
        connector.setHost(baseUri.getHost());
        server.addConnector(connector);
 
        // Create the ResourceHandler. It is the object that will actually handle the request for a given file. It is
        // a Jetty Handler object so it is suitable for chaining with other handlers as you will see in other examples.
        ResourceHandler resourceHandler = new ResourceHandler();
        // Configure the ResourceHandler. Setting the resource base indicates where the files should be served out of.
        // In this example it is the current directory but it can be configured to anything that the jvm has access to.
        resourceHandler.setDirectoriesListed(true);
        resourceHandler.setWelcomeFiles(new String[]{ INDEX_HTML });
        
        String rootPath = config.getString(Config.KEY_WEB_SERVER_WEB_ROOT_PATH);
        resourceHandler.setResourceBase(rootPath);
        
        File root = new File(rootPath);
        logger.info("web root: " + root.getCanonicalPath());
        
        if (!root.exists()) {
        	throw new IllegalArgumentException("web root " + rootPath + " doesn't exist");
        }
                
        // some ContextHandler is needed to enable symlinks and 
        // the ErrorHandler assumes that there is a ServletContext available 
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setHandler(resourceHandler);
        contextHandler.addAliasCheck(new AllowSymLinkAliasChecker());
        
        // generate a better error message if the ErrorHandler page is missing instead of the 
        // vague NullPointerException
        File indexFile = new File(root, INDEX_HTML); 
        if (!indexFile.exists()) {
        	logger.warn("index.html " + indexFile + " doesn't exist");
        }
        
        // let the app handle pushState URLs
        contextHandler.setErrorHandler(new NotFoundErrorHandler());
                
        // Add the ResourceHandler to the server.
        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[] { contextHandler, new DefaultHandler() });
        
        server.setHandler(handlers);
        
        StatusSource stats = RestUtils.createStatisticsListener(server);
        
        // Start things up! By using the server.join() the server thread will join with the current thread.
        // See "http://docs.oracle.com/javase/1.5.0/docs/api/java/lang/Thread.html#join()" for more details.
        server.start();
        //server.join();
        
        adminServer = RestUtils.startAdminServer(Role.WEB_SERVER, config, authService, stats);
	}

	public static void main(String[] args) throws Exception {
		WebServer service = new WebServer(new Config());

		RestUtils.shutdownGracefullyOnInterrupt(service.server, Role.WEB_SERVER);

		service.start();
	}

	public void close() {
		RestUtils.shutdown("web-server-admin", adminServer);
		try {
			server.stop();
		} catch (Exception e) {
			logger.warn("failed to stop the web server", e);
		}
	}
}
