package fi.csc.chipster.rest;

import javax.servlet.ServletException;
import javax.websocket.DeploymentException;
import javax.ws.rs.client.WebTarget;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.websocket.WebSocketClient;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

public class TestServerLauncher {
	
	// this must not be static, otherwise logging configuration fails
	@SuppressWarnings("unused")
	private final Logger logger = LogManager.getLogger();
	
	private ServiceLocatorClient serviceLocatorClient;

	private String targetUri;

	private ServerLauncher serverLauncher;

	private Level webSocketLoggingLevel;
	
	public TestServerLauncher(Config config, String role) throws ServletException, DeploymentException, InterruptedException {
		this(config, role, true);
	}
	
	public TestServerLauncher(Config config, String role, boolean quiet) throws ServletException, DeploymentException, InterruptedException {
		
		if (quiet) {
			webSocketLoggingLevel = Config.getLoggingLevel(WebSocketClient.class.getName());
			// hide messages about websocket connection status
			Config.setLoggingLevel(WebSocketClient.class.getName(), Level.OFF);
		}
		
		this.serverLauncher = new ServerLauncher(config, role, false);

		if (Role.AUTHENTICATION_SERVICE.equals(role)) {
			this.targetUri = config.getString("authentication-service");
			return;
		} 
		if (Role.SERVICE_LOCATOR.equals(role)) {
			this.targetUri = config.getString("service-locator");
		}
		
		this.serviceLocatorClient = new ServiceLocatorClient(config);
		
		if (Role.SESSION_DB.equals(role)) {			
			this.targetUri = serviceLocatorClient.get(Role.SESSION_DB).get(0);			
		}
	}		

	public void stop() {
		serverLauncher.stop();
		if (webSocketLoggingLevel != null) {
			// revert websocket logging
			Config.setLoggingLevel(WebSocketClient.class.getName(), webSocketLoggingLevel);
		}
	}	
	
	public WebTarget getUser1Target() {
		return new AuthenticationClient(serviceLocatorClient, "client", "clientPassword").getAuthenticatedClient().target(targetUri);
	}
	
	public WebTarget getUser2Target() {
		return new AuthenticationClient(serviceLocatorClient, "client2", "client2Password").getAuthenticatedClient().target(targetUri);
	}
	
	public WebTarget getSchedulerTarget() {
		return new AuthenticationClient(serviceLocatorClient, "scheduler", "schedulerPassword").getAuthenticatedClient().target(targetUri);
	}
	
	public WebTarget getCompTarget() {
		return new AuthenticationClient(serviceLocatorClient, "comp", "compPassword").getAuthenticatedClient().target(targetUri);
	}
	
	public WebTarget getSessionStorageUserTarget() {
		return new AuthenticationClient(serviceLocatorClient, "sessionStorage", "sessionStoragePassword").getAuthenticatedClient().target(targetUri);
	}
	
	public WebTarget getUnparseableTokenTarget() {
		return AuthenticationClient.getClient("token", "unparseableToken", true).target(targetUri);
	}
	
	public WebTarget getTokenFailTarget() {
		return AuthenticationClient.getClient("token", RestUtils.createId(), true).target(targetUri);
	}
	
	public WebTarget getAuthFailTarget() {
		// password login should be enabled only on auth, but this tries to use it on the session storage
		return AuthenticationClient.getClient("client", "clientPassword", true).target(targetUri);
	}
	
	public WebTarget getNoAuthTarget() {
		return AuthenticationClient.getClient().target(targetUri);
	}

	public ServerLauncher getServerLauncher() {
		return serverLauncher;
	}
}
