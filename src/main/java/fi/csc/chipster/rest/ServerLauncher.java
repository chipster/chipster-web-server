package fi.csc.chipster.rest;

import javax.ws.rs.client.WebTarget;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.AuthenticationService;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.servicelocator.ServiceLocator;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.SessionDb;

public class ServerLauncher {
	
	// this must not be static, otherwise logging configuration fails
	@SuppressWarnings("unused")
	private final Logger logger = LogManager.getLogger();
	
	private ServiceLocatorClient serviceLocatorClient;

	private String targetUri;

	private AuthenticationService auth;
	private ServiceLocator serviceLocator;
	private SessionDb sessionDb;	
	
	public ServerLauncher(Config config, String role) {
		auth = new AuthenticationService(config);
		auth.startServer();
		if (Role.AUTHENTICATION_SERVICE.equals(role)) {
			this.targetUri = config.getString("authentication-service");
			return;
		}
		serviceLocator = new ServiceLocator(config);
		serviceLocator.startServer();
		serviceLocatorClient = new ServiceLocatorClient(config);
		if (Role.SERVICE_LOCATOR.equals(role)) {
			this.targetUri = config.getString("service-locator");
			return;
		}
		sessionDb = new SessionDb(config);
		sessionDb.startServer();
		this.targetUri = serviceLocatorClient.get(Role.SESSION_DB).get(0);
	}		

	public void stop() {
		if (sessionDb != null) {
			sessionDb.close();
		}
		if (serviceLocator != null) {
			serviceLocator.close();
		}
		if (auth != null) {
			auth.close();			
		}		
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
	
	public static void main(String[] args) {
		Config config = new Config();
		new ServerLauncher(config, Role.SESSION_DB);
	}
}
