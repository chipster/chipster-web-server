package fi.csc.chipster.rest;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.UserToken;
import fi.csc.chipster.auth.resource.AuthTokens;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Jwts.SIG;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.WebTarget;

public class TestServerLauncher {
	

	// this must not be static, otherwise logging configuration fails
	@SuppressWarnings("unused")
	private final Logger logger = LogManager.getLogger();
	
	public static final String UNIT_TEST_USER1 = "unitTestUser1";
	public static final String UNIT_TEST_USER2 = "unitTestUser2";
	
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
			/*
			 *  WebSocket tests create ugly logs
			 * 
			 *  We could hide them, but that would make debugging more difficult.
			 */
			//Config.setLoggingLevel(PubSubServer.class.getPackage().getName(), Level.OFF);
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
		return new AuthenticationClient(serviceLocatorClient, UNIT_TEST_USER1, "clientPassword", Role.CLIENT).getAuthenticatedClient();
	}

	public Client getUser2Client() {
		return new AuthenticationClient(serviceLocatorClient, UNIT_TEST_USER2, "client2Password", Role.CLIENT).getAuthenticatedClient();
	}
	
	public Client getMonitoringClient() {
		return new AuthenticationClient(serviceLocatorClient, Role.MONITORING, Role.MONITORING, Role.CLIENT).getAuthenticatedClient();
	}
	
	public WebTarget getUser1Target(String role) {
		return getUser1Client().target(getTargetUri(role));
	}

	public WebTarget getUser2Target(String role) {
		return getUser2Client().target(getTargetUri(role));
	}
	
	public WebTarget getSchedulerTarget(String role) {
		return new AuthenticationClient(serviceLocatorClient, Role.SCHEDULER, Role.SCHEDULER, Role.SERVER).getAuthenticatedClient().target(getTargetUri(role));
	}
	
	public WebTarget getCompTarget(String role) {
		return new AuthenticationClient(serviceLocatorClient, Role.COMP, Role.COMP, Role.SERVER).getAuthenticatedClient().target(getTargetUri(role));
	}
	
	public WebTarget getSessionStorageUserTarget(String role) {
		return new AuthenticationClient(serviceLocatorClient, Role.SESSION_DB, Role.SESSION_DB, Role.SERVER).getAuthenticatedClient().target(getTargetUri(role));
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
		return AuthenticationClient.getClient(UNIT_TEST_USER1, "clientPassword", true);
	}

	public Client getNoAuthClient() {
		return AuthenticationClient.getClient();
	}
	
	public WebTarget getNoAuthTarget(String role) {
		return getNoAuthClient().target(getTargetUri(role));
	}
	
	public CredentialsProvider getUser1Token() {
		return new AuthenticationClient(serviceLocatorClient, UNIT_TEST_USER1, "clientPassword", Role.CLIENT).getCredentials();
	}

	public CredentialsProvider getUser2Token() {
		return new AuthenticationClient(serviceLocatorClient, UNIT_TEST_USER2, "client2Password", Role.CLIENT).getCredentials();
	}
	
	public CredentialsProvider getSchedulerToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.SCHEDULER, Role.SCHEDULER, Role.SERVER).getCredentials();
	}
	
	public CredentialsProvider getSessionWorkerToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.SESSION_WORKER, Role.SESSION_WORKER, Role.SERVER).getCredentials();
	}
	
	public CredentialsProvider getFileBrokerToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.FILE_BROKER, Role.FILE_BROKER, Role.SERVER).getCredentials();
	}
	
	public CredentialsProvider getSessionDbToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.SESSION_DB, Role.SESSION_DB, Role.SERVER).getCredentials();
	}
	
	public CredentialsProvider getAdminToken() {
		return new AuthenticationClient(serviceLocatorClient, Role.ADMIN, Role.ADMIN, Role.CLIENT).getCredentials();
	}
	
	public CredentialsProvider getUnparseableToken() {
		return new StaticCredentials("token", "unparseableToken");
	}
	
	/**
	 * Get token without signature at all
	 * 
	 * Without signature the user could create any kind of token for himself/herself.
	 * 
	 * @return
	 */
	public CredentialsProvider getUnsignedToken() {
		CredentialsProvider userToken = new AuthenticationClient(serviceLocatorClient, UNIT_TEST_USER1, "clientPassword", Role.CLIENT).getCredentials();
		String tokenKey = userToken.getPassword();
		
		String jwt = getHeader(tokenKey) + "." + AuthTokens.getPayload(tokenKey) + ".";
		
		return new StaticCredentials("token", jwt);
	}
	
	/**
	 * Replace the token signature with a signature of another (valid) token
	 * 
	 * Obviously these tokens shouldn't be allowed. Tokens have different usernames 
	 * and thus the signature of other token shouldn't work for this token.
	 * 
	 * @return
	 */
	public CredentialsProvider getSignatureFailToken() {
		CredentialsProvider userToken1 = new AuthenticationClient(serviceLocatorClient, UNIT_TEST_USER1, "clientPassword", Role.CLIENT).getCredentials();
		CredentialsProvider userToken2 = new AuthenticationClient(serviceLocatorClient, UNIT_TEST_USER2, "client2Password", Role.CLIENT).getCredentials();
		String tokenKey1 = userToken1.getPassword();
		String tokenKey2 = userToken2.getPassword();
				
		String signatureFailToken = 
		        getHeader(tokenKey1) + "."
		                + AuthTokens.getPayload(tokenKey1)
		                + "." + getSignature(tokenKey2);
		
		return new StaticCredentials("token", signatureFailToken);
	}
	
	public static String getHeader(String jws) {
        
        int payloadStartIndex = jws.indexOf(".");
        
        String header = jws.substring(0, payloadStartIndex);
        
        return header;
    }
    	
	public static String getSignature(String jws) {
		String signature = jws.substring(jws.lastIndexOf(".") + 1);
		return signature;
	}
		
	public static CredentialsProvider getWrongKeyToken() {
		
		String token = AuthTokens.createUserToken(
				UNIT_TEST_USER1, 
				new HashSet<String>(Arrays.asList(new String[] { Role.CLIENT, Role.SERVER, Role.ADMIN })),
				Instant.now(), 
				SIG.ES512.keyPair().build().getPrivate(), Jwts.SIG.ES512, "John Doe");
		
		return new StaticCredentials("token", token);
	}
	
	public CredentialsProvider getExpiredToken() {
		
		String token = AuthTokens.createUserToken(
				UNIT_TEST_USER1, 
				new HashSet<String>(Arrays.asList(new String[] { Role.CLIENT, Role.SERVER, Role.ADMIN })),
				Instant.now().minus(60, ChronoUnit.DAYS), 
				SIG.ES512.keyPair().build().getPrivate(), Jwts.SIG.ES512, "John Doe");
		
		return new StaticCredentials("token", token);
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
	    
	    CredentialsProvider userToken1 = new AuthenticationClient(serviceLocatorClient, UNIT_TEST_USER1, "clientPassword", Role.CLIENT).getCredentials();
        String tokenKey1 = userToken1.getPassword();
        
        String jwt = AuthTokens.jwsToJwt(tokenKey1);
		
		return new StaticCredentials("token", jwt);
	}
	
	/**
	 * The algorithm is send in the JWT header
	 * 
	 * Make sure the server doesn't accept tokens that are signed with the public key (as a symmetric key)
	 * https://auth0.com/blog/critical-vulnerabilities-in-json-web-token-libraries/
	 * 
	 * @return
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeySpecException 
	 */
	public CredentialsProvider getSymmetricToken() throws IOException, InvalidKeySpecException, NoSuchAlgorithmException {
		
		byte[] publicKey = new AuthenticationClient(new ServiceLocatorClient(config), "session-db", "session-db", Role.SERVER).getJwtPublicKey().getEncoded();
						
		/* 
		 * https://stackoverflow.com/a/68377829
		 * 
		 * "SecretKey[Spec] classes and their subclasses are used only for symmetric algorithms, 
		 * including HMAC, and the PrivateKey[Spec] classes and their subclasses are used only 
		 * for asymmetric algorithms"
		 */
		SecretKeySpec symmetricKey = new SecretKeySpec(publicKey, HmacAlgorithms.HMAC_SHA_512.getName());
		
		// have to sign a token here, because AuthTokens.createUserToken() doesn't (correctly) 
		// accept a symmetric key
		
		HashSet<String> roles = new HashSet<String>(Arrays.asList(new String[] { Role.CLIENT, Role.SERVER, Role.ADMIN }));
		String rolesString = String.join(AuthTokens.ROLES_DELIMITER, roles);
        Instant now = Instant.now();
        Instant expiration = AuthTokens.getUserTokenNextExpiration(now, roles);
        
        String token = Jwts.builder()
                .issuer("chipster")
                .subject(UNIT_TEST_USER1)
                .audience().add("chipster").and()
                .expiration(Date.from(expiration))
                .notBefore(Date.from(now)) 
                .issuedAt(Date.from(now))
                .claim(AuthTokens.CLAIM_KEY_CLASS, UserToken.class.getName())
                .claim(AuthTokens.CLAIM_KEY_NAME, "John Doe")
                .claim(AuthTokens.CLAIM_KEY_ROLES, rolesString)
                .claim(AuthTokens.CLAIM_KEY_LOGIN_TIME, Instant.now().getEpochSecond())
                .signWith(symmetricKey, Jwts.SIG.HS512)
                .compact();
		
		return new StaticCredentials("token", token);
	}	
	
	public CredentialsProvider getUser1Credentials() {
		return new StaticCredentials("jaas/" + TestServerLauncher.UNIT_TEST_USER1, "clientPassword");
	}
	
	public CredentialsProvider getUser2Credentials() {
		return new StaticCredentials("jaas/" + TestServerLauncher.UNIT_TEST_USER2, "client2Password");
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
	
	public ServiceLocatorClient getServiceLocatorForScheduler() throws IOException {
		ServiceLocatorClient client = new ServiceLocatorClient(config);
		client.setCredentials(this.getSchedulerToken());
		return client;
	}

	public Client getAdminClient() {
		return AuthenticationClient.getClient("admin", "admin", true);
	}
}
