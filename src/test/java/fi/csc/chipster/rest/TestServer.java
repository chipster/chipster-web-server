package fi.csc.chipster.rest;

import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.rest.token.AuthenticatedTarget;

public class TestServer {
	
	private static Logger logger = Logger.getLogger(TestServer.class.getName());
	
	public WebTarget testTarget;
	HashMap<Server, HttpServer> httpServers = new HashMap<>();

	private Server server;
	private Server authServer;	
	
	public TestServer(Server server, Server authServer) {
		this.server = server;
		this.authServer = authServer;
	}
	
	public void startServersIfNecessary() {

		startServerIfNecessary(authServer);
		startServerIfNecessary(server);
	}
	
	private void startServerIfNecessary(Server server) {
		if (server != null) {
			testTarget = AuthenticatedTarget.getClient(null, null, false).target(server.getBaseUri());

			try {
				// check if the server is already running
				testTarget.path(server.getBaseUri()).request().get(Response.class);
			} catch (ProcessingException e) {
				// start the server
				httpServers.put(server, server.startServer());
			}
		} 
	}

	public void stop() {
		stop(server);
		stop(authServer);
	}
		
	private void stop(Server server) {
		
		if (httpServers.containsKey(server)) {
			server.close();
			HttpServer httpServer = httpServers.get(server);
			GrizzlyFuture<HttpServer> future = httpServer.shutdown();
			try {
				// wait for server to shutdown, otherwise the next test set will print ugly log messages
				try {
					future.get(3, TimeUnit.SECONDS);
				} catch (TimeoutException e) {
					logger.log(Level.WARNING, "test server didn't stop gracefully");
					httpServer.shutdownNow();
				}
			} catch (InterruptedException | ExecutionException e) {
				logger.log(Level.WARNING, "failed to shutdown the test server", e);
			}
		}
	}
	
	public WebTarget getUser1Target() {
		return new AuthenticatedTarget("client", "clientPassword").target(getBaseUri());
	}
	
	public WebTarget getUser2Target() {
		return new AuthenticatedTarget("client2", "client2Password").target(getBaseUri());
	}
	
	public WebTarget getTokenFailTarget() {
		return AuthenticatedTarget.getClient("token", "wrongToken", true).target(getBaseUri());
	}
	
	public WebTarget getAuthFailTarget() {
		// password login should be enabled only on auth, but this tries to use it on the sessions storage
		return AuthenticatedTarget.getClient("client", "clientPassword", true).target(getBaseUri());
	}
	
	public WebTarget getNoAuthTarget() {
		return AuthenticatedTarget.getClient(null, null, false).target(getBaseUri());
	}

	private String getBaseUri() {
		if (server != null) {
			return server.getBaseUri();
		}
		return authServer.getBaseUri();
	}
}
