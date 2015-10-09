package fi.csc.chipster.rest;

import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationService;
import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.servicelocator.ServiceLocator;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.SessionDb;

public class ServerLauncher {
	
	// this must not be static, otherwise logging configuration fails
	private final Logger logger = LogManager.getLogger();
	
	HashMap<Server, HttpServer> httpServers = new HashMap<>();

	private Server server;
	private Server authServer;

	private ServiceLocator serviceLocator;

	private ServiceLocatorClient serviceLocatorClient;

	private String targetUri;

	private String role;

	private Config config;

	public ServerLauncher(Config config, Server server, String role) {
		if (config != null) {
			try {
				// check if the server is already running
				new ServiceLocatorClient(config).getServices(Role.AUTHENTICATION_SERVICE);
			} catch (ProcessingException e) {
				logger.error("service locator didn't respond");
				logger.info("starting auth");
				authServer = new AuthenticationService(config);
				httpServers.put(authServer, authServer.startServer());

				logger.info("starting service locator");
				// start the server
				serviceLocator = new ServiceLocator(config);
				httpServers.put(serviceLocator, serviceLocator.startServer());
			}

			this.serviceLocatorClient = new ServiceLocatorClient(config);
		}
		this.config = config;
		this.server = server;
		this.role = role;
	}

	public void startServersIfNecessary() {

		startServerIfNecessary(server);
	}
	
	private void startServerIfNecessary(Server server) {
		if (server != null) {
			WebTarget testTarget = AuthenticationClient.getClient().target(server.getBaseUri());

			try {
				// check if the server is already running
				testTarget.path(server.getBaseUri()).request().get(Response.class);
			} catch (ProcessingException e) {
				// start the server
				httpServers.put(server, server.startServer());
			}
		}
		
		if (Role.SERVICE_LOCATOR.equals(role)) {
			this.targetUri = config.getString("service-locator");
		} else if (role != null) {
			this.targetUri = serviceLocatorClient.get(role).get(0);
		}
	}

	public void stop() {
		stop(server);
		stop(serviceLocator);
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
					logger.warn("test server didn't stop gracefully");
					httpServer.shutdownNow();
				}
			} catch (InterruptedException | ExecutionException e) {
				logger.warn("failed to shutdown the test server", e);
			}
		}
	}
	
	public WebTarget getUser1Target() {
		return new AuthenticationClient(serviceLocatorClient, "client", "clientPassword").getAuthenticatedClient().target(getBaseUri());
	}
	
	public WebTarget getUser2Target() {
		return new AuthenticationClient(serviceLocatorClient, "client2", "client2Password").getAuthenticatedClient().target(getBaseUri());
	}
	
	public WebTarget getSessionStorageUserTarget() {
		return new AuthenticationClient(serviceLocatorClient, "sessionStorage", "sessionStoragePassword").getAuthenticatedClient().target(getBaseUri());
	}
	
	public WebTarget getUnparseableTokenTarget() {
		return AuthenticationClient.getClient("token", "unparseableToken", true).target(getBaseUri());
	}
	
	public WebTarget getTokenFailTarget() {
		return AuthenticationClient.getClient("token", RestUtils.createId(), true).target(getBaseUri());
	}
	
	public WebTarget getAuthFailTarget() {
		// password login should be enabled only on auth, but this tries to use it on the session storage
		return AuthenticationClient.getClient("client", "clientPassword", true).target(getBaseUri());
	}
	
	public WebTarget getNoAuthTarget() {
		return AuthenticationClient.getClient().target(getBaseUri());
	}

	private String getBaseUri() {
		return targetUri;
	}

	public Server getServer() {
		return server;
	}
	
	public static void main(String[] args) {
		Config config = new Config();
		ServerLauncher launcher = new ServerLauncher(config, new SessionDb(config), null);
		launcher.startServersIfNecessary();
	}
}
