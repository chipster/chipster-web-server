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
	
	public WebTarget testTarget;
	private HttpServer httpServer;
	private HttpServer httpAuthServer;

	private Server server;
	private Server authServer;	
	
	public TestServer(Server server, Server authServer) {
		this.server = server;
		this.authServer = authServer;
	}
	
	public void startServersIfNecessary() {

		httpAuthServer = startServerIfNecessary(authServer);
		httpServer = startServerIfNecessary(server);
	}
	
	private HttpServer startServerIfNecessary(Server server) {
		if (server != null) {
			testTarget = AuthenticatedTarget.getClient(null, null, false).target(server.getBaseUri());

			try {
				// check if the server is already running
				testTarget.path(server.getBaseUri()).request().get(Response.class);
			} catch (ProcessingException e) {
				// start the server
				return server.startServer();
			}
		}
		return null; 
	}

	public void stop() {
		stop(httpServer);
		stop(httpAuthServer);
	}
		
	private void stop(HttpServer httpServer) {
		
		if (httpServer != null) {
			GrizzlyFuture<HttpServer> future = httpServer.shutdown();
			try {
				// wait for server to shutdown, otherwise the next test set will print ugly log messages
				future.get();
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
