package fi.csc.chipster.web;

import java.io.File;
import java.net.URI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;

import fi.csc.chipster.rest.Config;

public class WebServer {
	
	private static final Logger logger = LogManager.getLogger();
	
	private Config config;
	private Server server;

	public WebServer(Config config) {
		this.config = config;
	}

	public void start() throws Exception {
		
        URI baseUri = config.getURI("web-bind");
        
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
        resourceHandler.setWelcomeFiles(new String[]{ "index.html" });
        
        String rootPath = config.getString("web-root-path");
        resourceHandler.setResourceBase(rootPath);
        
        File root = new File(rootPath);
        logger.info("web root: " + root.getCanonicalPath());
        
        if (!root.exists()) {
        	throw new IllegalArgumentException("web root " + rootPath + " doesn't exist");
        }
        
        // Add the ResourceHandler to the server.
        
        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[] { resourceHandler, new DefaultHandler() });
        
        server.setHandler(handlers);
        
        // Start things up! By using the server.join() the server thread will join with the current thread.
        // See "http://docs.oracle.com/javase/1.5.0/docs/api/java/lang/Thread.html#join()" for more details.
        server.start();
        //server.join();
	}

	public static void main(String[] args) throws Exception {
		new WebServer(new Config()).start();
	}

	public void close() {
		//RestUtils.waitForShutdown("web server", httpServer);
		try {
			server.stop();
		} catch (Exception e) {
			logger.warn("failed to stop the web server", e);
		}
	}
}