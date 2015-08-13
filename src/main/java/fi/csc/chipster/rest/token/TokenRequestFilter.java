package fi.csc.chipster.rest.token;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;

import javax.annotation.Priority;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.Priorities;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.Provider;

import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.rest.AuthPrincipal;
import fi.csc.chipster.auth.rest.AuthSecurityContext;
import fi.csc.chipster.auth.rest.AuthenticationService;
import fi.csc.chipster.rest.AuthenticatedTarget;
import fi.csc.chipster.rest.provider.NotAuthorizedException;

@Provider
@Priority(Priorities.AUTHENTICATION) // execute this filter before others
public class TokenRequestFilter implements ContainerRequestFilter {
	
	public static final String TOKEN_USER = "token";
	private static final int CACHE_MAX_SIZE = 1000;
	
	//FIXME read credentials from config
	private WebTarget authTarget = new AuthenticatedTarget("sessionStorage", "sessionStoragePassword").target(new AuthenticationService().getBaseUri());
	
	private LinkedHashMap<String, Token> tokenCache = new LinkedHashMap<>();

	@Override
	public void filter(ContainerRequestContext requestContext)
			throws IOException {
		
//		long t = System.currentTimeMillis();
		
		BasicAuthParser parser = new BasicAuthParser(requestContext.getHeaderString("authorization"));
		
		if (!TOKEN_USER.equals(parser.getUsername())) {
			throw new NotAuthorizedException("only tokens allowed");
		}

		// throws an exception if fails
		AuthPrincipal principal = tokenAuthentication(parser.getPassword());
		
		// login ok
		requestContext.setSecurityContext(
				new AuthSecurityContext(principal, requestContext.getSecurityContext()));
		
//		System.out.println("token validation " + (System.currentTimeMillis() - t) + " ms");
	}

	public AuthPrincipal tokenAuthentication(String clientTokenKey) {
        
		Token dbClientToken = null;
		
		if (tokenCache.containsKey(clientTokenKey)) {
			dbClientToken = tokenCache.get(clientTokenKey);
		} else {
			dbClientToken = getDbToken(clientTokenKey);
			tokenCache.put(clientTokenKey, dbClientToken);
			
			Iterator<String> iter = tokenCache.keySet().iterator();
			while (tokenCache.size() > CACHE_MAX_SIZE) {
				//TODO is this the oldest?
				iter.remove();
			}			
		}
    	
        if (dbClientToken == null) {
        	throw new ForbiddenException();
        }
        
        if (dbClientToken.getValid().isBefore(LocalDateTime.now())) {
        	throw new ForbiddenException();
        }
		
		return new AuthPrincipal(dbClientToken.getUsername(), clientTokenKey, dbClientToken.getRoles());
	}

	private Token getDbToken(String clientTokenKey) {
		return authTarget
    			.path("tokens")
    			.request(MediaType.APPLICATION_JSON_TYPE)
    		    .header("chipster-token", clientTokenKey)
    		    .get(Token.class);
	}
}