package fi.csc.chipster.rest;

import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpServer;

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
		
		target = AuthenticatedTarget.getClient(null, null, enableBasicAuth).target(service.getBaseUri());
				
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
