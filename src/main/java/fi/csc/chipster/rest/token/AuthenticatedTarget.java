package fi.csc.chipster.rest.token;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.media.sse.SseFeature;

import fi.csc.chipster.auth.AuthenticationService;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.rest.provider.LocalDateTimeContextResolver;

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
		c.register(LocalDateTimeContextResolver.class);
		c.register(SseFeature.class);
		return c;
	}


	public WebTarget target(String baseUri) {
		return client.target(baseUri);
	}
}
