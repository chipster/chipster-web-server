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

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.toolbox.resource.ModuleResource;
import fi.csc.chipster.toolbox.resource.ToolResource;

/**
 * Main class.
 *
 */
public class ToolboxService {

	@SuppressWarnings("unused")
	private Logger logger = LogManager.getLogger();

	private Config config;

	public ToolboxService(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
	 * application.
     * @return Grizzly HTTP server.
     * @throws URISyntaxException 
     */
    public HttpServer startServer() throws IOException, URISyntaxException {

    	Toolbox toolbox = new Toolbox(new File("../chipster-tools/modules"));
    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
        	.register(new ToolResource(toolbox))
        	.register(new ModuleResource(toolbox));
        	//.register(new LoggingFilter())

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
        return GrizzlyHttpServerFactory.createHttpServer(
                URI.create(getBaseUri()), rc);
    }

    /**
     * Main method.
     * @throws URISyntaxException 
     */
    public static void main(String[] args) throws IOException, URISyntaxException {

        final HttpServer server = new ToolboxService(new Config()).startServer();
        RestUtils.waitForShutdown("toolbox", server);
    }

	public void close() {
	}

	public final String getBaseUri() {
		return this.config.getString("toolbox-bind");
	}
}

