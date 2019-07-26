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

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.openssl.PEMException;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.hibernate.service.spi.ServiceException;

import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.auth.resource.AuthUserResource;
import fi.csc.chipster.auth.resource.JwsUtils;
import fi.csc.chipster.auth.resource.TokenResource;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.DynamicCredentials;
import fi.csc.chipster.rest.JavaTimeObjectMapperProvider;
import fi.csc.chipster.rest.RestMethods;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;


public class AuthenticationClient {
	
	private static Logger logger = LogManager.getLogger();

	private ServiceLocatorClient serviceLocator;

	private Token token;
	private String username;
	private String password;

	private String authenticationServiceUri;
	
	private Timer tokenRefreshTimer;
	private Duration TOKEN_REFRESH_INTERVAL = Duration.of(1, ChronoUnit.HOURS); // UNIT MUST BE DAYS OR SHORTER
	
	private DynamicCredentials dynamicCredentials = new DynamicCredentials();

	public AuthenticationClient(ServiceLocatorClient serviceLocator, String username, String password) {
		this.serviceLocator = serviceLocator;
		construct(username, password);
	}

	public AuthenticationClient(String authUri, String username, String password) {
		this.authenticationServiceUri = authUri;
		construct(username, password);
	}

	private void construct(String username, String password) {
		this.username = username;
		this.password = password;
		
		setToken(getTokenFromAuth());
		
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

	/**
	 * Succeeds or throws
	 * 
	 * @return
	 */
	private Token getTokenFromAuth() {
		String authUri = getAuth();

		if (authUri == null) {
			throw new InternalServerErrorException("no auth in service locator");
		}
			
		Client authClient = getClient(username, password, true);
		WebTarget authTarget = authClient.target(authUri);
		
		logger.info("get token from " + authUri);

		Token serverToken = authTarget
				.path("tokens")
				.request(MediaType.APPLICATION_JSON_TYPE)
				.post(Entity.json(""), Token.class);		

		return serverToken;
	}

	/**
	 * Get Auth's URI
	 * 
	 * We use auth's public URI, because we can't get the internal from the service-locator before 
	 * we have authenticated. In theory we could use the public URI for the first authentication and 
	 * internal afterwards, but then misconfiguration of the internal URI would be difficult to notice and 
	 * would cause problems later unexpectedly.  
	 * 
	 * @return
	 */
	private String getAuth() {
		if (serviceLocator != null) {
			return serviceLocator.getPublicUri(Role.AUTH);
		} else {
			return authenticationServiceUri;
		}
	}
	
	public static Client getClient(String username, String password, boolean enableAuth) {

		Client c = ClientBuilder.newClient()
				.register(JacksonJaxbJsonProvider.class)
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
		return getClient(TokenRequestFilter.TOKEN_USER, token.getTokenKey().toString(), true);
	}

	public Token getDbToken(String tokenKey) {
		
		String authUri = getAuth();

		try {
			Token dbToken = getAuthenticatedClient()
					.target(authUri)
					.path(TokenResource.PATH_TOKENS)
					.request(MediaType.APPLICATION_JSON_TYPE)
					.header("chipster-token", tokenKey)
					.get(Token.class);

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

	public String getTokenKey() {
		return token.getTokenKey();
	}
	
	private void refreshToken() {
		String authUri = getAuth();
				
		try {
			Token serverToken = getAuthenticatedClient()
					.target(authUri)
					.path(TokenResource.PATH_TOKENS)
					.path("refresh")
					.request(MediaType.APPLICATION_JSON_TYPE)
					.post(Entity.json(""), Token.class);

			if (serverToken != null) {
				setToken(serverToken); 
				
				// if token is expiring before refresh interval * 2, get a new token
				if (serverToken.getValidUntil().isBefore(Instant.now().plus(TOKEN_REFRESH_INTERVAL.multipliedBy(2)))) {
					logger.info("refreshed token expiring soon, getting a new one (" + this.username + ")");
					try {
						setToken(getTokenFromAuth());
						logger.info("new token valid until " + token.getValidUntil() + " (" + this.username + ")");
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
				setToken(getTokenFromAuth());
				logger.info("new token valid until " + token.getValidUntil() + " (" + this.username + ")");
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

	private void setToken(Token token) {
		this.token = token;
		this.dynamicCredentials.setCredentials(TokenRequestFilter.TOKEN_USER, this.token.getTokenKey().toString());		
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
				
				// refresh token if the laptop has been sleeping or something
				if (token.getValidUntil().isBefore(Instant.now().plus(TOKEN_REFRESH_INTERVAL))) {
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
				.path(URLEncoder.encode(userId.toUserIdString(), StandardCharsets.UTF_8.name())), User.class);
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

	public PublicKey getJwtPublicKey() throws PEMException {
		String authUri = getAuth();

		String pem = getAuthenticatedClient()
				.target(authUri)
				.path(TokenResource.PATH_TOKENS)
				.path(TokenResource.PATH_PUBLIC_KEY)
				.request(MediaType.TEXT_PLAIN_TYPE)
				.get(String.class);
		
		return JwsUtils.pemToPublicKey(pem);
	}
}

