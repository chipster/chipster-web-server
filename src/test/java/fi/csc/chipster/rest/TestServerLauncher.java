package fi.csc.chipster.rest;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.resource.AuthTokens;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

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
		} else if (Role.SERVICE_LOCATOR.equals(role)) {
			return config.getInternalServiceUrls().get(Role.SERVICE_LOCATOR);
		} else if (serviceLocatorClient.getPublicUri(role) != null) {			
			return serviceLocatorClient.getPublicUri(role);			
		} else {
			throw new IllegalArgumentException("no target uri for role " + role);
		}
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
	
	public Client getWrongTokenClient() {
		return AuthenticationClient.getClient("token", getWrongKeyToken().getPassword(), true);
	}
	
	public WebTarget getWrongTokenTarget(String role) {
		return getWrongTokenClient().target(getTargetUri(role));
	}
	
	public WebTarget getAuthFailTarget(String role) {
		// password login should be enabled only on auth, but this tries to use it on the session storage
		return getAuthFailClient().target(getTargetUri(role));
	}
	
	public Client getAuthFailClient() {
		return AuthenticationClient.getClient("client", "clientPassword", true);
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
	
	public CredentialsProvider getSessionWorkerToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.SESSION_WORKER, Role.SESSION_WORKER).getCredentials();
	}
	
	public CredentialsProvider getFileBrokerToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.FILE_BROKER, Role.FILE_BROKER).getCredentials();
	}
	
	public CredentialsProvider getSessionDbToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.SESSION_DB, Role.SESSION_DB).getCredentials();
	}
	
	public CredentialsProvider getAdminToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.ADMIN, Role.ADMIN).getCredentials();
	}
	
	public CredentialsProvider getUnparseableToken() {
		return new StaticCredentials("token", "unparseableToken");
	}
		
	public static CredentialsProvider getWrongKeyToken() {
		
		Token token = AuthTokens.createToken(
				"client", 
				new HashSet<String>(Arrays.asList(new String[] { Role.CLIENT, Role.SERVER, Role.ADMIN })),
				Instant.now(), 
				Keys.keyPairFor(SignatureAlgorithm.ES512).getPrivate(), SignatureAlgorithm.ES512);
		
		return new StaticCredentials("token", token.getTokenKey());
	}
	
	public CredentialsProvider getExpiredToken() {
		
		Token token = AuthTokens.createToken(
				"client", 
				new HashSet<String>(Arrays.asList(new String[] { Role.CLIENT, Role.SERVER, Role.ADMIN })),
				Instant.now().minus(60, ChronoUnit.DAYS), 
				Keys.keyPairFor(SignatureAlgorithm.ES512).getPrivate(), SignatureAlgorithm.ES512);
		
		return new StaticCredentials("token", token.getTokenKey());
	}
	
	/**
	 * The algorithm is send in the JWT header
	 * 
	 * Make sure the server doesn't accept NONE signatures
	 * https://auth0.com/blog/critical-vulnerabilities-in-json-web-token-libraries/
	 * 
	 * @return
	 */
	public CredentialsProvider getNoneToken() {
		
		String jws = Jwts.builder()
			    .setIssuer("chipster")
			    .setSubject("client")
			    .setAudience("chipster")
			    .setExpiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
			    .setNotBefore(Date.from(Instant.now())) 
			    .setIssuedAt(Date.from(Instant.now()))
			    .setId(UUID.randomUUID().toString())
			    .claim(AuthTokens.CLAIM_KEY_ROLES, Role.CLIENT + " " + Role.SERVER + " " + Role.ADMIN)
			    .claim(AuthTokens.CLAIM_KEY_LOGIN_TIME, Instant.now().getEpochSecond())			  
			    .compact();
		
		return new StaticCredentials("token", jws);
	}
	
	/**
	 * The algorithm is send in the JWT header
	 * 
	 * Make sure the server doesn't accept tokens that are signed with the public key (as a symmetric key)
	 * https://auth0.com/blog/critical-vulnerabilities-in-json-web-token-libraries/
	 * 
	 * @return
	 * @throws IOException 
	 */
	public CredentialsProvider getSymmetricToken() throws IOException {
		
		byte[] publicKey = new AuthenticationClient(new ServiceLocatorClient(config), "session-db", "session-db").getJwtPublicKey().getEncoded();
		
		SecretKeySpec symmetricKey = new SecretKeySpec(publicKey, SignatureAlgorithm.HS512.getJcaName());
		
		Token token = AuthTokens.createToken(
				"client", 
				new HashSet<String>(Arrays.asList(new String[] { Role.CLIENT, Role.SERVER, Role.ADMIN })),
				Instant.now(), 
				symmetricKey, SignatureAlgorithm.HS512);
		
		return new StaticCredentials("token", token.getTokenKey());
	}	
	
	public CredentialsProvider getUser1Credentials() {
		return new StaticCredentials("jaas/client", "clientPassword");
	}
	
	public CredentialsProvider getUser2Credentials() {
		return new StaticCredentials("jaas/client2", "client2Password");
	}
	

	public ServerLauncher getServerLauncher() {
		return serverLauncher;
	}

	public ServiceLocatorClient getServiceLocator() {
		return serviceLocatorClient;
	}
	
	public ServiceLocatorClient getServiceLocatorForAdmin() throws IOException {
		ServiceLocatorClient client = new ServiceLocatorClient(config);
		client.setCredentials(this.getAdminToken());
		return client;
	}

	public Client getAdminClient() {
		return AuthenticationClient.getClient("admin", "admin", true);
	}
}
