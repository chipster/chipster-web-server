package fi.csc.chipster.rest;

import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;

import fi.csc.chipster.rest.provider.ObjectMapperContextResolver;
import fi.csc.chipster.sessionstorage.rest.Server;

public class TestServer {
	
	private static Logger logger = Logger.getLogger(TestServer.class.getName());
	
	public WebTarget target;
	private HttpServer server;

	private Server service;	
	
	public TestServer(Server service) {
		this.service = service;
	}
	
	public WebTarget getTarget() {
		return getTarget(true);
	}

	public WebTarget getTarget(boolean enableBasicAuth) {
		
		// create the client
		Client c = ClientBuilder.newClient();
		if (enableBasicAuth) {
			HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic("user", "password");
			c.register(feature);
		}
		c.register(ObjectMapperContextResolver.class);

		target = c.target(service.getBaseUri());
				
		try {
			// check if the server is already running
			target.path(service.getBaseUri()).request().get(Response.class);
		} catch (ProcessingException e) {
			// start the server
			server = service.startServer();
		}

		return target;
	}

	public void stop(Object test) {
		if (server != null) {
			GrizzlyFuture<HttpServer> future = server.shutdown();
			try {
				// wait for server to shutdown, otherwise the next test set will print ugly log messages
				future.get();
			} catch (InterruptedException | ExecutionException e) {
				logger.log(Level.WARNING, "failed to shutdown the test server", e);
			}
		}
	}
}
