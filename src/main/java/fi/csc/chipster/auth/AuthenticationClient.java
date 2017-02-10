package fi.csc.chipster.auth;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
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
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.exception.LocalDateTimeContextResolver;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

public class AuthenticationClient {
	
	private static Logger logger = LogManager.getLogger();

	private ServiceLocatorClient serviceLocator;

	private UUID token;

	private List<String> authenticationServiceUris;

	public AuthenticationClient(ServiceLocatorClient serviceLocator, String username, String password) {
		this.serviceLocator = serviceLocator;
		
		construct(username, password);
	}
	
	public AuthenticationClient(String authUri, String username,
			String password) {
		this.authenticationServiceUris = Arrays.asList(new String[] {authUri});
		
		construct(username, password);
	}

	public AuthenticationClient(List<String> auths, String username,
			String password) {
		this.authenticationServiceUris = auths;
		
		construct(username, password);
	}

	private void construct(String username, String password) {
		List<String> auths = getAuths();
		
		if (auths.size() == 0) {
			throw new InternalServerErrorException("no auths registered to service locator");
		}
		
		for (String authUri : auths) {
			try {
				logger.info("get token from " + authUri);
				token = getToken(authUri, username, password);
				break;
			} catch (ServiceException e) {
				logger.warn("auth not available", e);
			}
		}
	}

	private UUID getToken(String authUri, String username, String password) {
		Client authClient = getClient(username, password, true);
		WebTarget authTarget = authClient.target(authUri);
		
		Token serverToken = authTarget
    			.path("tokens")
    			.request(MediaType.APPLICATION_JSON_TYPE)
    		    .post(null, Token.class);		
        
        return serverToken.getTokenKey();
	}

	private List<String> getAuths() {
		if (serviceLocator != null) {
			return serviceLocator.get(Role.AUTHENTICATION_SERVICE);
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
		return getClient(TokenRequestFilter.TOKEN_USER, token.toString(), true);
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

	public UUID getToken() {
		return token;
	}
	
	/**
	 * @return
	 */
	public CredentialsProvider getCredentials() {
		return new StaticCredentials(TokenRequestFilter.TOKEN_USER, token.toString());		
	}
}

