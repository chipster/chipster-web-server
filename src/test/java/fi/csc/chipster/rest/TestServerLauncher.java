package fi.csc.chipster.rest;

import javax.ws.rs.client.WebTarget;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

public class TestServerLauncher {
	
	// this must not be static, otherwise logging configuration fails
	@SuppressWarnings("unused")
	private final Logger logger = LogManager.getLogger();
	
	private ServiceLocatorClient serviceLocatorClient;

	private String targetUri;

	private ServerLauncher serverLauncher;
	
	public TestServerLauncher(Config config, String role) {
		this(config, role, false);
	}
	
	public TestServerLauncher(Config config, String role, boolean verbose) {
		
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
	}	
	
	public WebTarget getUser1Target() {
		return new AuthenticationClient(serviceLocatorClient, "client", "clientPassword").getAuthenticatedClient().target(targetUri);
	}
	
	public WebTarget getUser2Target() {
		return new AuthenticationClient(serviceLocatorClient, "client2", "client2Password").getAuthenticatedClient().target(targetUri);
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
}
