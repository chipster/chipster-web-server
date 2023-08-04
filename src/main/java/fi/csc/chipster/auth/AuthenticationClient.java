package fi.csc.chipster.auth;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.openssl.PEMException;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.hibernate.service.spi.ServiceException;

import fi.csc.chipster.auth.model.ChipsterToken;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.SessionToken.Access;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.auth.model.UserToken;
import fi.csc.chipster.auth.resource.AuthTokenResource;
import fi.csc.chipster.auth.resource.AuthTokens;
import fi.csc.chipster.auth.resource.AuthUserResource;
import fi.csc.chipster.auth.resource.JwsUtils;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.DynamicCredentials;
import fi.csc.chipster.rest.JavaTimeObjectMapperProvider;
import fi.csc.chipster.rest.RestMethods;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;


public class AuthenticationClient {
	
	private static Logger logger = LogManager.getLogger();

	private ServiceLocatorClient serviceLocator;

	private String token;
	private String username;
	private String password;

	private String authenticationServiceUri;
	
	private Timer tokenRefreshTimer;
	
	private Duration TOKEN_REFRESH_INTERVAL = Duration.of(1, ChronoUnit.HOURS); // UNIT MUST BE DAYS OR SHORTER
	
	private DynamicCredentials dynamicCredentials = new DynamicCredentials();

	private volatile PublicKey jwtPublicKey;

	private String role;

	/**
	 * @param serviceLocator
	 * @param username
	 * @param password
	 * @param role Role.CLIENT or Role.SERVER. Only servers validate the token
	 */
	public AuthenticationClient(ServiceLocatorClient serviceLocator, String username, String password, String role) {
		this.serviceLocator = serviceLocator;
		this.role = role;
		construct(username, password, true);
	}

	public AuthenticationClient(String authUri, String username, String password, String role) {		
		this.authenticationServiceUri = authUri;
		this.role = role;
		construct(username, password, true);
	}

	public AuthenticationClient(String authUri, String username, String password, String role, boolean startTokenRefreshTimer) {
		this.authenticationServiceUri = authUri;
		this.role = role;
		construct(username, password, startTokenRefreshTimer);
	}

	private void construct(String username, String password, boolean startTokenRefreshTimer) {
		this.username = username;
		this.password = password;
		
		setToken(getUserTokenFromAuth());
		
		if (startTokenRefreshTimer) {
			// schedule token refresh
			this.tokenRefreshTimer = new Timer("auth-client-token-refresh-timer", true);
			tokenRefreshTimer.schedule(new TimerTask() {
	
				@Override
				public void run() {
					try {
						refreshToken();
					} catch (Exception e) {
						logger.warn("refresh token failed",  e);
					}
				}
				
			}, TOKEN_REFRESH_INTERVAL.toMillis(), TOKEN_REFRESH_INTERVAL.toMillis());
		}
	}

	/**
	 * Succeeds or throws
	 * 
	 * @return
	 */
	private String getUserTokenFromAuth() {

		String authUri = getAuth(username, password);

		if (authUri == null) {
			throw new InternalServerErrorException("no auth in service locator");
		}
			
		Client authClient = getClient(username, password, true);
		WebTarget authTarget = authClient.target(authUri);
		
		logger.info("get token from " + authUri + " for " + username);

		String serverToken = authTarget
				.path("tokens")
				.request(MediaType.TEXT_PLAIN)
				.post(Entity.json(""), String.class);		

		return serverToken;
	}

	/**
	 * Get Auth's URI in the initialization when we don't yet have a token
	 * 
	 * In the initialization (or after failures) we have to use username and password to authenticate to the
	 * service-locator. Normally we would authenticate with a token, but we need 
	 * the internal address of the auth service to acquire one.
	 */
	private String getAuth(String username, String password) {
		if (authenticationServiceUri != null) {
			/* auth uses localhost to call its own API and
			 * service-locator has the auth address in the configuration
			 */
			return authenticationServiceUri;
		} else {
			if(Role.SERVER.equals(this.role)) {
				logger.info("get auth address from the service-locator using username and password");
				ServiceLocatorClient passwordClient = new ServiceLocatorClient(this.serviceLocator.getBaseUri());
				passwordClient.setCredentials(new StaticCredentials(username, password));
				return passwordClient.getInternalService(Role.AUTH).getUri();
			} else {
				// unit tests use this as client
				return this.serviceLocator.getPublicUri(Role.AUTH);
			}
		}
	}
	
	/**
	 * Get Auth's URI when we have a working token
	 */
	public String getAuth() {
		if (authenticationServiceUri != null) {
			return authenticationServiceUri;
		} else {
			if(Role.SERVER.equals(this.role)) {
				return this.serviceLocator.getInternalService(Role.AUTH).getUri();
			} else {
				// unit tests use this as client
				return this.serviceLocator.getPublicUri(Role.AUTH);
			}
		}
	}
	
	public static Client getClient(String username, String password, boolean enableAuth) {

		Client c = ClientBuilder.newClient()
				.register(JacksonFeature.class)
				.register(JavaTimeObjectMapperProvider.class)
				;
		if (enableAuth) {
			HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(username, password);
			c.register(feature);
		}
		
		return c;
	}
	
	public static Client getClient() {
		return getClient(null, null, false);
	}

	public Client getAuthenticatedClient() {
		return getClient(TokenRequestFilter.TOKEN_USER, token, true);
	}

	public String validateTokenKeyFromAuth(String tokenKey) {
		
		String authUri = getAuth();

		try {
			String dbToken = getAuthenticatedClient()
					.target(authUri)
					.path(AuthTokenResource.PATH_TOKENS)
					.request(MediaType.TEXT_PLAIN)
					.header(AuthTokenResource.TOKEN_HEADER, tokenKey)
					.get(String.class);

			if (dbToken != null) {
				return dbToken;
			}
		} catch (ServiceException e) {
			logger.warn("auth not available", e);
		} catch (NotFoundException e) {
			return null;
		}	
		return null;
	}

	public String getToken() {
		return token;
	}
	
	private void refreshToken() {
		String authUri = getAuth();
				
		try {
			String serverToken = getAuthenticatedClient()
					.target(authUri)
					.path(AuthTokenResource.PATH_TOKENS)
					.path("refresh")
					.request(MediaType.TEXT_PLAIN)
					.post(Entity.json(""), String.class);

			if (serverToken != null) {
				UserToken parsedToken = setToken(serverToken); 
				
				// if token is expiring before refresh interval * 2, get a new token
				if (parsedToken.getValidUntil().isBefore(Instant.now().plus(TOKEN_REFRESH_INTERVAL.multipliedBy(2)))) {
					logger.info("refreshed token expiring soon, getting a new one (" + this.username + ")");
					try {
						parsedToken = setToken(getUserTokenFromAuth());
						logger.info("new token valid until " + parsedToken.getValidUntil() + " (" + this.username + ")");
					} catch (Exception e) {
						logger.warn("getting new token to replace soon expiring token failed (" + this.username + ")", e);
					}
				}
				
				return;
			} else {
				// is it possible to get here?
				logger.warn("got null as response to refresh token (" + this.username + ")");
			}
				
		} catch (ForbiddenException fe) {
			logger.info("got forbidden when refreshing token, getting new one (" + this.username + ")");
			try {
				UserToken parsedToken = setToken(getUserTokenFromAuth());
				logger.info("new token valid until " + parsedToken.getValidUntil() + " (" + this.username + ")");
				return;
			} catch (Exception e) {
				logger.warn("getting new token after forbidden failed (" + this.username + ")", e);
			}
		} catch (ServiceException e) {
			logger.warn("auth not available (" + this.username + ")", e);
		} catch (Exception e) {
			logger.warn("refresh token failed (" + this.username + ")", e);
		}

		logger.warn("refresh token failing (" + this.username + ")");
	}

	private UserToken setToken(String token) {
		this.token = token;
		this.dynamicCredentials.setCredentials(TokenRequestFilter.TOKEN_USER, this.token);
		
		if (Role.SERVER.equals(this.role)) {
			// public key is visible only for the servers for now 
			return validateUserToken(token);
		} else {
			return AuthTokens.decodeUserToken(token);
		}
	}

	/**
	 * @return
	 */
	public CredentialsProvider getCredentials() {
		return new CredentialsProvider() {
			@Override
			public String getUsername() {
				return dynamicCredentials.getUsername();
			}

			@Override
			public String getPassword() {
				
				UserToken parsedToken = null;
				
				if (Role.SERVER.equals(role)) {
					parsedToken = validateUserToken(token);
				} else {
					parsedToken = AuthTokens.decodeUserToken(token);
				}
				
				// refresh token if the laptop has been sleeping or something
				if (parsedToken.getValidUntil().isBefore(Instant.now().plus(TOKEN_REFRESH_INTERVAL))) {
					logger.warn("timer hasn't refreshed the token in time, trying now");
					refreshToken();
				}
								
				return dynamicCredentials.getPassword();
			}
		};
	}
	
	public User getUser(UserId userId) throws RestException {
		return AuthenticationClient.getUser(userId, getAuthenticatedClient(), serviceLocator);
	}
	
	public static User getUser(UserId userId, Client client, ServiceLocatorClient serviceLocator) throws RestException {
		try {
			return RestMethods.get(client
				.target(serviceLocator.getPublicUri(Role.AUTH))
				.path(AuthUserResource.USERS)
				.queryParam(AuthUserResource.USER_ID_KEY, URLEncoder.encode(userId.toUserIdString(), StandardCharsets.UTF_8.name())), User.class);
		} catch (UnsupportedEncodingException e) {
			// convert to UncheckedException, because there is nothing the caller can do for this
			throw new RuntimeException(e);
		}
	}
	
	public List<User> getUsers() throws RestException {
		return AuthenticationClient.getUsers(getAuthenticatedClient(), serviceLocator);
	}
	
	public static List<User> getUsers(Client client, ServiceLocatorClient serviceLocator) throws RestException {
		return RestMethods.getList(client
			.target(serviceLocator.getPublicUri(Role.AUTH))
			.path(AuthUserResource.USERS), User.class);
	}
	
	public UserToken validateUserToken(String token) {
		
		// validation is possible only for servers
		if (!Role.SERVER.equals(this.role)) {
			// public key is visible only for the servers for now 
			throw new IllegalStateException("only servers can validate tokens");
		}
		
		PublicKey jwtPublicKey = getJwtPublicKeyCached();
		
		return AuthTokens.validateUserToken(token, jwtPublicKey);
	}
	
	public Jws<Claims> validateTokenSignature(String token) {
		
		// validation is possible only for servers
		if (!Role.SERVER.equals(this.role)) {
			// public key is visible only for the servers for now 
			throw new IllegalStateException("only servers can validate tokens");
		}
		
		PublicKey jwtPublicKey = getJwtPublicKeyCached();
		
		return AuthTokens.validateSignature(token, jwtPublicKey);
	}
	
	public PublicKey getJwtPublicKey() throws PEMException {
		String authUri = getAuth(username, password);
		
		logger.info("get JWT public key from " + authUri + " for " + username);

		String pem = getAuthenticatedClient()
				.target(authUri)
				.path(AuthTokenResource.PATH_TOKENS)
				.path(AuthTokenResource.PATH_PUBLIC_KEY)
				.request(MediaType.TEXT_PLAIN_TYPE)
				.get(String.class);
		
		return JwsUtils.pemToPublicKey(pem);
	}
	
	public PublicKey getJwtPublicKeyCached() {
		// double checked locking with volatile field http://rpktech.com/2015/02/04/lazy-initialization-in-multi-threaded-environment/
		// to make this safe and relatively fast for multi-thread usage
		if (this.jwtPublicKey == null) {
			synchronized (this) {
				if (this.jwtPublicKey == null) {
					try {
						this.jwtPublicKey = this.getJwtPublicKey();						
					} catch (PEMException e) {
						throw new RuntimeException("unable to get the public key", e);
					}
				}
			}
		}
		
		return this.jwtPublicKey;
	}
	
	public String createSessionToken(String username, UUID sessionId, Instant valid, Access access) throws RestException {
		
		String authUri = getAuth();
		
		WebTarget target = getAuthenticatedClient()
		.target(authUri)
		.path(AuthTokenResource.PATH_TOKENS)
		.path(AuthTokenResource.PATH_SESSION_TOKEN);
		
		if (username != null) { 
			target = target.queryParam(AuthTokenResource.QP_USERNAME, username);
		}
	
		if (sessionId != null) {
			target = target.queryParam(AuthTokenResource.QP_SESSION_ID, sessionId.toString());
		}
		
		if (valid != null) {
			target = target.queryParam(AuthTokenResource.QP_VALID, valid.toString());
		}
		
		if (access != null) {
			target = target.queryParam(AuthTokenResource.QP_ACCESS, access.name());
		}
		
		String datasetToken = target
				.request(MediaType.TEXT_PLAIN)
				.post(Entity.json(""), String.class);
		
		return datasetToken;
	}
	
	public String createDatasetToken(String username, UUID sessionId, UUID datasetId, Instant valid) throws RestException {
		
		String authUri = getAuth();
		
		WebTarget target = getAuthenticatedClient()
		.target(authUri)
		.path(AuthTokenResource.PATH_TOKENS)
		.path(AuthTokenResource.PATH_DATASET_TOKEN);
		
		if (username != null) { 
			target = target.queryParam(AuthTokenResource.QP_USERNAME, username);
		}
	
		if (sessionId != null) {
			target = target.queryParam(AuthTokenResource.QP_SESSION_ID, sessionId.toString());
		}
		
		if (datasetId != null) {
			target = target.queryParam(AuthTokenResource.QP_DATASET_ID, datasetId.toString());
		}
		
		if (valid != null) {
			target = target.queryParam(AuthTokenResource.QP_VALID, valid.toString());
		}
		
		String datasetToken = target
				.request(MediaType.TEXT_PLAIN)
				.post(Entity.json(""), String.class);
		
		return datasetToken;
	}

	public <T extends ChipsterToken> boolean isTokenClass(Claims claims, Class<T> tokenClass) {
		return AuthTokens.isTokenClass(claims, tokenClass);
	}

}

