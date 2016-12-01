package fi.csc.chipster.rest;

import java.io.IOException;

import javax.ws.rs.client.WebTarget;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

public class TestServerLauncher {
	
	// this must not be static, otherwise logging configuration fails
	@SuppressWarnings("unused")
	private final Logger logger = LogManager.getLogger();
	
	private ServiceLocatorClient serviceLocatorClient;
	private ServerLauncher serverLauncher;
	private Level webSocketLoggingLevel;
	private Config config;
	
	public TestServerLauncher(Config config) throws Exception {
		this(config, true);
	}
	
	public TestServerLauncher(Config config, boolean quiet) throws Exception {
		
		this.config = config;
		
		if (quiet) {
			webSocketLoggingLevel = Config.getLoggingLevel(PubSubServer.class.getPackage().getName());
			// hide messages about websocket connection status
			Config.setLoggingLevel(PubSubServer.class.getPackage().getName(), Level.OFF);
		}
		
		//this.serverLauncher = new ServerLauncher(config, false);
		
		this.serviceLocatorClient = new ServiceLocatorClient(config);		
	}		

	public void stop() {
		//serverLauncher.stop();
		if (webSocketLoggingLevel != null) {
			// revert websocket logging
			Config.setLoggingLevel(PubSubServer.class.getPackage().getName(), webSocketLoggingLevel);
		}
	}	
	
	private String getTargetUri(String role) {
		try {
			if (Role.AUTHENTICATION_SERVICE.equals(role)) {
					return config.getString("authentication-service");
			} 
			if (Role.SERVICE_LOCATOR.equals(role)) {
				return config.getString("service-locator");
			}
			
			if (Role.SESSION_DB.equals(role)) {			
				return serviceLocatorClient.get(Role.SESSION_DB).get(0);			
			}
			
			if (Role.FILE_BROKER.equals(role)) {			
				return serviceLocatorClient.get(Role.FILE_BROKER).get(0);			
			}
					
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		throw new IllegalArgumentException("no target uri for role " + role);
	}
	
	public WebTarget getUser1Target(String role) {
		return new AuthenticationClient(serviceLocatorClient, "client", "clientPassword").getAuthenticatedClient().target(getTargetUri(role));
	}

	public WebTarget getUser2Target(String role) {
		return new AuthenticationClient(serviceLocatorClient, "client2", "client2Password").getAuthenticatedClient().target(getTargetUri(role));
	}
	
	public WebTarget getSchedulerTarget(String role) {
		return new AuthenticationClient(serviceLocatorClient, Config.USERNAME_SCHEDULER, Config.USERNAME_SCHEDULER).getAuthenticatedClient().target(getTargetUri(role));
	}
	
	public WebTarget getCompTarget(String role) {
		return new AuthenticationClient(serviceLocatorClient, Config.USERNAME_COMP, Config.USERNAME_COMP).getAuthenticatedClient().target(getTargetUri(role));
	}
	
	public WebTarget getSessionStorageUserTarget(String role) {
		return new AuthenticationClient(serviceLocatorClient, Config.USERNAME_SESSION_DB, Config.USERNAME_SESSION_DB).getAuthenticatedClient().target(getTargetUri(role));
	}
	
	public WebTarget getUnparseableTokenTarget(String role) {
		return AuthenticationClient.getClient("token", "unparseableToken", true).target(getTargetUri(role));
	}
	
	public WebTarget getTokenFailTarget(String role) {
		return AuthenticationClient.getClient("token", RestUtils.createId(), true).target(getTargetUri(role));
	}
	
	public WebTarget getAuthFailTarget(String role) {
		// password login should be enabled only on auth, but this tries to use it on the session storage
		return AuthenticationClient.getClient("client", "clientPassword", true).target(getTargetUri(role));
	}
	
	public WebTarget getNoAuthTarget(String role) {
		return AuthenticationClient.getClient().target(getTargetUri(role));
	}
	
	public CredentialsProvider getUser1Token() {
		return new AuthenticationClient(serviceLocatorClient, "client", "clientPassword").getCredentials();
	}

	public CredentialsProvider getUser2Token() {
		return new AuthenticationClient(serviceLocatorClient, "client2", "client2Password").getCredentials();
	}
	
	public CredentialsProvider getSchedulerToken() {
		return new AuthenticationClient(serviceLocatorClient, Config.USERNAME_SCHEDULER, Config.USERNAME_SCHEDULER).getCredentials();
	}
	
	public CredentialsProvider getCompToken() {
		return new AuthenticationClient(serviceLocatorClient, Config.USERNAME_COMP, Config.USERNAME_COMP).getCredentials();
	}
	
	public CredentialsProvider getFileBrokerToken() {
		return new AuthenticationClient(serviceLocatorClient, Config.USERNAME_FILE_BROKER, Config.USERNAME_FILE_BROKER).getCredentials();
	}
	
	public CredentialsProvider getSessionStorageUserToken() {
		return new AuthenticationClient(serviceLocatorClient, Config.USERNAME_SESSION_DB, Config.USERNAME_SESSION_DB).getCredentials();
	}
	
	public CredentialsProvider getUnparseableToken() {
		return new StaticCredentials("token", "unparseableToken");
	}
		
	public CredentialsProvider getWrongToken() {
		return new StaticCredentials("token", RestUtils.createId());
	}
	
	public CredentialsProvider getUsernameAndPassword() {
		return new StaticCredentials("client", "clientPassword");
	}

	public ServerLauncher getServerLauncher() {
		return serverLauncher;
	}

	public ServiceLocatorClient getServiceLocator() {
		return serviceLocatorClient;
	}
}
