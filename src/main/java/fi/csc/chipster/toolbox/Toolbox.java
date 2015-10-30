package fi.csc.chipster.toolbox;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.toolbox.resource.ToolResource;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import java.io.IOException;
import java.net.URI;

/**
 * Main class.
 *
 */
public class Toolbox {

	@SuppressWarnings("unused")
	private Logger logger = LogManager.getLogger();

	private Config config;

	public Toolbox(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
	 * application.
     * @return Grizzly HTTP server.
     */
    public HttpServer startServer() {

    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
        	.register(new ToolResource());
			//.register(new LoggingFilter())

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
        return GrizzlyHttpServerFactory.createHttpServer(
                URI.create(getBaseUri()), rc);
    }

    /**
     * Main method.
     */
    public static void main(String[] args) throws IOException {

        final HttpServer server = new Toolbox(new Config()).startServer();
        RestUtils.waitForShutdown("toolbox", server);
    }

	public void close() {
	}

	public final String getBaseUri() {
		return this.config.getString("toolbox-bind");
	}
}

