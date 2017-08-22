package fi.csc.chipster.rest;

import javax.ws.rs.client.Client;
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
	
	public String getTargetUri(String role) {

		if (Role.AUTH.equals(role)) {
				return config.getInternalServiceUrls().get(Role.AUTH);
		} 
		if (Role.SERVICE_LOCATOR.equals(role)) {
			return config.getInternalServiceUrls().get(Role.SERVICE_LOCATOR);
		}
		
		if (Role.SESSION_DB.equals(role)) {			
			return serviceLocatorClient.get(Role.SESSION_DB).get(0);			
		}
		
		if (Role.FILE_BROKER.equals(role)) {			
			return serviceLocatorClient.get(Role.FILE_BROKER).get(0);			
		}
		
		if (Role.TYPE_SERVICE.equals(role)) {			
			return serviceLocatorClient.get(Role.TYPE_SERVICE).get(0);			
		}

		throw new IllegalArgumentException("no target uri for role " + role);
	}
	
	public Client getUser1Client() {
		return new AuthenticationClient(serviceLocatorClient, "client", "clientPassword").getAuthenticatedClient();
	}

	public Client getUser2Client() {
		return new AuthenticationClient(serviceLocatorClient, "client2", "client2Password").getAuthenticatedClient();
	}
	
	public Client getMonitoringClient() {
		return new AuthenticationClient(serviceLocatorClient, Role.MONITORING, Role.MONITORING).getAuthenticatedClient();
	}
	
	public WebTarget getUser1Target(String role) {
		return getUser1Client().target(getTargetUri(role));
	}

	public WebTarget getUser2Target(String role) {
		return getUser2Client().target(getTargetUri(role));
	}
	
	public WebTarget getSchedulerTarget(String role) {
		return new AuthenticationClient(serviceLocatorClient, Role.SCHEDULER, Role.SCHEDULER).getAuthenticatedClient().target(getTargetUri(role));
	}
	
	public WebTarget getCompTarget(String role) {
		return new AuthenticationClient(serviceLocatorClient, Role.COMP, Role.COMP).getAuthenticatedClient().target(getTargetUri(role));
	}
	
	public WebTarget getSessionStorageUserTarget(String role) {
		return new AuthenticationClient(serviceLocatorClient, Role.SESSION_DB, Role.SESSION_DB).getAuthenticatedClient().target(getTargetUri(role));
	}
	
	public Client getUnparseableTokenClient() {
		return AuthenticationClient.getClient("token", "unparseableToken", true);
	}
	
	public WebTarget getUnparseableTokenTarget(String role) {
		return getUnparseableTokenClient().target(getTargetUri(role));
	}
	
	public Client getTokenFailClient() {
		return AuthenticationClient.getClient("token", RestUtils.createId(), true);
	}
	
	public WebTarget getTokenFailTarget(String role) {
		return getTokenFailClient().target(getTargetUri(role));
	}
	
	public WebTarget getAuthFailTarget(String role) {
		// password login should be enabled only on auth, but this tries to use it on the session storage
		return AuthenticationClient.getClient("client", "clientPassword", true).target(getTargetUri(role));
	}
	
	public Client getNoAuthClient() {
		return AuthenticationClient.getClient();
	}
	
	public WebTarget getNoAuthTarget(String role) {
		return getNoAuthClient().target(getTargetUri(role));
	}
	
	public CredentialsProvider getUser1Token() {
		return new AuthenticationClient(serviceLocatorClient, "client", "clientPassword").getCredentials();
	}

	public CredentialsProvider getUser2Token() {
		return new AuthenticationClient(serviceLocatorClient, "client2", "client2Password").getCredentials();
	}
	
	public CredentialsProvider getSchedulerToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.SCHEDULER, Role.SCHEDULER).getCredentials();
	}
	
	public CredentialsProvider getCompToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.COMP, Role.COMP).getCredentials();
	}
	
	public CredentialsProvider getFileBrokerToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.FILE_BROKER, Role.FILE_BROKER).getCredentials();
	}
	
	public CredentialsProvider getSessionDbToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.SESSION_DB, Role.SESSION_DB).getCredentials();
	}
	
	public CredentialsProvider getUnparseableToken() {
		return new StaticCredentials("token", "unparseableToken");
	}
		
	public CredentialsProvider getWrongToken() {
		return new StaticCredentials("token", RestUtils.createId());
	}
	
	public CredentialsProvider getUser1Credentials() {
		return new StaticCredentials("client", "clientPassword");
	}
	
	public CredentialsProvider getUser2Credentials() {
		return new StaticCredentials("client2", "client2Password");
	}

	public ServerLauncher getServerLauncher() {
		return serverLauncher;
	}

	public ServiceLocatorClient getServiceLocator() {
		return serviceLocatorClient;
	}
}
