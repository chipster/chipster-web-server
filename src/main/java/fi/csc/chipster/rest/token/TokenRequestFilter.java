package fi.csc.chipster.rest.token;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;

import javax.annotation.Priority;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.auth.resource.AuthSecurityContext;
import fi.csc.chipster.rest.exception.NotAuthorizedException;

@Provider
@Priority(Priorities.AUTHENTICATION) // execute this filter before others
public class TokenRequestFilter implements ContainerRequestFilter {
	
	public static final String QUERY_PARAMETER_TOKEN = "token";
	public static final String HEADER_AUTHORIZATION = "authorization";
	public static final String TOKEN_USER = "token";
	private static final int CACHE_MAX_SIZE = 1000;
	
	private LinkedHashMap<String, Token> tokenCache = new LinkedHashMap<>();
	private boolean authenticationRequired = true;
	
	private AuthenticationClient authService;

	public TokenRequestFilter(AuthenticationClient authService) {
		this.authService = authService;
	}

	@Override
	public void filter(ContainerRequestContext requestContext)
			throws IOException {
		
//		long t = System.currentTimeMillis();
		if ("OPTIONS".equals(requestContext.getMethod())) {
			
			// CORS preflight checks require unauthenticated OPTIONS
			return;
		}
		
		String authHeader = requestContext.getHeaderString(HEADER_AUTHORIZATION);
		// allow token to be sent also as a query parameter, because JS EventSource doesn't allow setting headers 
		String authParameter = requestContext.getUriInfo().getQueryParameters().getFirst(QUERY_PARAMETER_TOKEN);
		
		String password = getToken(authHeader, authParameter);
		
		if (password == null && !authenticationRequired) {
			// this filter is configured to pass non-authenticated users through
			requestContext.setSecurityContext(
					new AuthSecurityContext(new AuthPrincipal(null, Role.UNAUTHENTICATED), requestContext.getSecurityContext()));
			return;
		}		

		// throws an exception if fails
		AuthPrincipal principal = tokenAuthentication(password);
		
		// login ok
		requestContext.setSecurityContext(
				new AuthSecurityContext(principal, requestContext.getSecurityContext()));
		
//		System.out.println("token validation " + (System.currentTimeMillis() - t) + " ms");
	}

	public String getToken(String authHeader, String authParameter) {
		if (authHeader != null) {
			BasicAuthParser parser = new BasicAuthParser(authHeader);
			if (!TOKEN_USER.equals(parser.getUsername())) {
				throw new NotAuthorizedException("only tokens allowed");
			}
			return parser.getPassword();
		} else {
			return authParameter;
		}
	}

	public AuthPrincipal tokenAuthentication(String clientTokenKey) {
		
		if (clientTokenKey == null) {
			throw new NotAuthorizedException("no authorization header");
		}
        
		Token dbClientToken = null;
		
		if (tokenCache.containsKey(clientTokenKey)) {
			dbClientToken = tokenCache.get(clientTokenKey);
		} else {
			dbClientToken = authService.getDbToken(clientTokenKey);
			if (dbClientToken == null) {
				throw new ForbiddenException("token not found");	
			}
			tokenCache.put(clientTokenKey, dbClientToken);
			
			Iterator<String> iter = tokenCache.keySet().iterator();
			while (tokenCache.size() > CACHE_MAX_SIZE) {
				//TODO is this the oldest?
				iter.remove();
			}			
		}
        
        if (dbClientToken.getValid().isBefore(LocalDateTime.now())) {
        	throw new ForbiddenException();
        }
		
		return new AuthPrincipal(dbClientToken.getUsername(), clientTokenKey, dbClientToken.getRoles());
	}

	public void authenticationRequired(boolean authenticationRequired) {
		this.authenticationRequired  = authenticationRequired;
	}
}