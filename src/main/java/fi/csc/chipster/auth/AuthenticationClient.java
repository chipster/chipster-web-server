package fi.csc.chipster.auth;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.media.sse.SseFeature;
import org.hibernate.service.spi.ServiceException;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.resource.TokenResource;
import fi.csc.chipster.rest.exception.LocalDateTimeContextResolver;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

public class AuthenticationClient {
	
	private static Logger logger = Logger.getLogger(AuthenticationClient.class.getName());

	private ServiceLocatorClient serviceLocator;

	private UUID token;

	private List<String> authenticationServiceUris;

	public AuthenticationClient(ServiceLocatorClient serviceLocator, String username, String password) {
		this.serviceLocator = serviceLocator;
		
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
				token = getToken(authUri, username, password);
				break;
			} catch (ServiceException e) {
				logger.log(Level.WARNING, "auth not available", e);
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
		c.register(SseFeature.class);
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
				logger.log(Level.WARNING, "auth not available", e);
			}
		}
		return null;
	}
}
