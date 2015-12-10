package fi.csc.chipster.toolbox;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.toolbox.resource.ModuleResource;
import fi.csc.chipster.toolbox.resource.ToolResource;
import fi.csc.chipster.auth.model.Role;

/**
 * Main class.
 *
 */
public class ToolboxService {

	private Logger logger = LogManager.getLogger();

	private Config config;
	private HttpServer httpServer;

	@SuppressWarnings("unused")
	private String serviceId;
	
	public ToolboxService(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
	 * application.
     * @return Grizzly HTTP server.
     * @throws URISyntaxException 
     */
    public void startServer() throws IOException, URISyntaxException {

    	Toolbox toolbox = new Toolbox(new File("../chipster-tools/modules"));
    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
        	.register(new ToolResource(toolbox))
        	.register(new ModuleResource(toolbox));
        	//.register(new LoggingFilter())

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
    	URI baseUri = URI.create(this.config.getString(Config.KEY_TOOLBOX_BIND_URL));
    	this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);

    	// try to register this toolbox to the service locator
    	// fails if service locator not up, toolbox service will still be functional though
    	try {
    		registerToServiceLocator(config);
    	} catch (Exception e) {
    		logger.info("register to service locator failed");
    	}
    }

    /**
     * Main method.
     * @throws URISyntaxException 
     */
    public static void main(String[] args) throws IOException, URISyntaxException {
        final ToolboxService server = new ToolboxService(new Config());
        server.startServer();
        RestUtils.waitForShutdown("toolbox", server.getHttpServer());
    }

	private HttpServer getHttpServer() {
		return this.httpServer;
	}

	public void close() {
		RestUtils.shutdown("toolbox", httpServer);
	}

	private void registerToServiceLocator(Config config) {
    	String username = config.getString(Config.KEY_TOOLBOX_USERNAME);
    	String password = config.getString(Config.KEY_TOOLBOX_PASSWORD);

		ServiceLocatorClient locatorClient = new ServiceLocatorClient(config);
		AuthenticationClient authClient = new AuthenticationClient(locatorClient, username, password);

		this.serviceId = locatorClient.register(Role.TOOLBOX, authClient, config.getString(Config.KEY_TOOLBOX_URL));
	}
	
	
}

