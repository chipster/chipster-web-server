package fi.csc.chipster.sessionworker;

import java.io.IOException;
import java.net.URI;

import javax.servlet.ServletException;
import javax.websocket.DeploymentException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;

/**
 * Main class.
 *
 */
public class SessionWorker {

	@SuppressWarnings("unused")
	private Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private String serviceId;

	private ServiceLocatorClient serviceLocator;
	
	private Config config;

	private HttpServer httpServer;
	
	private SessionWorkerResource sessionWorkerResource;

	private AuthenticationClient authService;

	public SessionWorker(Config config) {
		this.config = config;
	}

	/**
	 * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
	 * application.
	 * 
	 * @return Grizzly HTTP server.
	 * @throws DeploymentException 
	 * @throws ServletException 
	 * @throws RestException 
	 */
	public void startServer() throws ServletException, DeploymentException, RestException {

		String username = "sessionWorker";
    	String password = "sessionWorkerPassword";    	
    	
    	this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);
		
		TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
		
		this.sessionWorkerResource = new SessionWorkerResource(serviceLocator);
		
		final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
				.register(sessionWorkerResource)
				.register(tokenRequestFilter);

		// create and start a new instance of grizzly http server
		// exposing the Jersey application at BASE_URI
		URI baseUri = URI.create(this.config.getString("session-worker-bind"));
		httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
	}

	/**
	 * Main method.
	 * 
	 * @param args
	 * @throws IOException
	 * @throws DeploymentException 
	 * @throws ServletException 
	 * @throws RestException 
	 */
	public static void main(String[] args) throws IOException, ServletException, DeploymentException, RestException {

		final SessionWorker server = new SessionWorker(new Config());
		server.startServer();
		
		RestUtils.waitForShutdown("session-worker", server.getHttpServer());
	}

	public void close() {
		RestUtils.shutdown("session-worker", httpServer);
	}
	
	public HttpServer getHttpServer() {
		return httpServer;
	}
}
