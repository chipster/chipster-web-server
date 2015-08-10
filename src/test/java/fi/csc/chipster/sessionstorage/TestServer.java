package fi.csc.chipster.sessionstorage;

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

import fi.csc.chipster.sessionstorage.rest.SessionStorage;

public class TestServer {
	
	private static Logger logger = Logger.getLogger(TestServer.class.getName());
	
	public WebTarget target;
	private HttpServer server;	
	
	public WebTarget getTarget(Object test) {
		
		// create the client
		Client c = ClientBuilder.newClient();

		target = c.target(SessionStorage.BASE_URI);
				
		try {
			// check if the server is already running
			target.path(SessionStorage.BASE_URI).request().get(Response.class);
		} catch (ProcessingException e) {
			// start the server
			server = SessionStorage.startServer();
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
