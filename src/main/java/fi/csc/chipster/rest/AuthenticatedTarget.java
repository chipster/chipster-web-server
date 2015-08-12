package fi.csc.chipster.rest;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;

import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.rest.AuthenticationService;
import fi.csc.chipster.rest.provider.ObjectMapperContextResolver;
import fi.csc.chipster.rest.token.TokenRequestFilter;

public class AuthenticatedTarget {

	private Client client;

	public AuthenticatedTarget(String username, String password) {
		Client authClient = getClient(username, password, true);
		WebTarget authTarget = authClient.target(new AuthenticationService().getBaseUri());
		
		Token serverToken = authTarget
    			.path("tokens")
    			.request(MediaType.APPLICATION_JSON_TYPE)
    		    .post(null, Token.class);
    	
        
        String tokenKey = serverToken.getTokenKey();
        
        this.client = getClient(TokenRequestFilter.TOKEN_USER, tokenKey, true);   
	}

	public static Client getClient(String username, String password, boolean enableAuth) {
		Client c = ClientBuilder.newClient();
		if (enableAuth) {
			HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(username, password);
			c.register(feature);
		}
		c.register(ObjectMapperContextResolver.class);
		return c;
	}


	public WebTarget target(String baseUri) {
		return client.target(baseUri);
	}
}
