package fi.csc.chipster.auth;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

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
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.hibernate.service.spi.ServiceException;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.resource.TokenResource;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.DynamicCredentials;
import fi.csc.chipster.rest.exception.LocalDateTimeContextResolver;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

public class AuthenticationClient {
	
	private static Logger logger = LogManager.getLogger();

	private ServiceLocatorClient serviceLocator;

	private Token token;
	private String username;
	private String password;

	private List<String> authenticationServiceUris;
	
	private Timer tokenRefreshTimer;
	private Duration TOKEN_REFRESH_INTERVAL = Duration.of(15, ChronoUnit.SECONDS); // UNIT MUST BE DAYS OR SHORTER
	
	private DynamicCredentials dynamicCredentials = new DynamicCredentials();

	public AuthenticationClient(ServiceLocatorClient serviceLocator, String username, String password) {
		this.serviceLocator = serviceLocator;
		construct(username, password);
	}
	
	public AuthenticationClient(String authUri, String username, String password) {
		this.authenticationServiceUris = Arrays.asList(new String[] {authUri});
		construct(username, password);
	}

	public AuthenticationClient(List<String> auths, String username, String password) {
		this.authenticationServiceUris = auths;
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
				refreshToken();
			}
			
		}, TOKEN_REFRESH_INTERVAL.toMillis(), TOKEN_REFRESH_INTERVAL.toMillis());
	}

	/**
	 * Succeeds or throws
	 * 
	 * @return
	 */
	private Token getTokenFromAuth() {
		List<String> auths = getAuths();

		if (auths.size() == 0) {
			throw new InternalServerErrorException("no auths registered to service locator");
		}

		for (String authUri : auths) {
			
			Client authClient = getClient(username, password, true);
			WebTarget authTarget = authClient.target(authUri);
			
			logger.info("get token from " + authUri);

			Token serverToken = authTarget
					.path("tokens")
					.request(MediaType.APPLICATION_JSON_TYPE)
					.post(Entity.json(""), Token.class);		

			return serverToken;

		}
		throw new RuntimeException("get token from auth failed");
	}

	private List<String> getAuths() {
		if (serviceLocator != null) {
			return serviceLocator.get(Role.AUTH);
		} else {
			return authenticationServiceUris;
		}
	}
	
	public static Client getClient(String username, String password, boolean enableAuth) {
		Client c = ClientBuilder.newClient();
		if (enableAuth) {
			HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(username, password);
			c.register(feature);
		}
		c.register(LocalDateTimeContextResolver.class);
		//c.register(new LoggingFilter());
		return c;
	}
	
	public static Client getClient() {
		return getClient(null, null, false);
	}

	public Client getAuthenticatedClient() {
		return getClient(TokenRequestFilter.TOKEN_USER, token.getTokenKey().toString(), true);
	}

	public Token getDbToken(String tokenKey) {
		List<String> auths = getAuths();

		for (String authUri : auths) {
			try {
				Token dbToken = getAuthenticatedClient()
						.target(authUri)
						.path(TokenResource.TOKENS)
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
		}
		return null;
	}

	public UUID getTokenKey() {
		return token.getTokenKey();
	}
	
	private void refreshToken() {
		
		for (String authUri : getAuths()) {
			try {
				Token serverToken = getAuthenticatedClient()
						.target(authUri)
						.path(TokenResource.TOKENS)
						.path("refresh")
						.request(MediaType.APPLICATION_JSON_TYPE)
						.post(Entity.json(""), Token.class);

				if (serverToken != null) {
					setToken(serverToken); 
					
					// if token is expiring before refresh interval * 2, get a new token
					if (serverToken.getValid().isBefore(LocalDateTime.now().plus(TOKEN_REFRESH_INTERVAL.multipliedBy(2)))) {
						logger.info("refreshed token expiring soon, getting a new one");
						try {
							setToken(getTokenFromAuth());
						} catch (Exception e) {
							logger.warn("getting new token to replace soon expiring token failed", e);
						}
					}
					
					return;
				} else {
					// is it possible to get here?
					logger.warn("got null as response to refresh token");
				}
					
			} catch (ForbiddenException fe) {
				logger.info("got forbidden when refreshing token, getting new one");
				try {
					setToken(getTokenFromAuth());
					return;
				} catch (Exception e) {
					logger.warn("getting new token after forbidden failed", e);
				}
			} catch (ServiceException e) {
				logger.warn("auth not available", e);
			} catch (Exception e) {
				logger.warn("refresh token failed", e);
			}
		}

	logger.warn("refresh token failing");
	}

	private void setToken(Token token) {
		this.token = token;
		this.dynamicCredentials.setCredentials(TokenRequestFilter.TOKEN_USER, this.token.getTokenKey().toString());
	}

	/**
	 * @return
	 */
	public CredentialsProvider getCredentials() {
		return this.dynamicCredentials;		
	}
}

