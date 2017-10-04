package fi.csc.chipster.rest.token;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.Priority;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.auth.resource.AuthSecurityContext;
import fi.csc.chipster.rest.exception.NotAuthorizedException;

@Provider
@Priority(Priorities.AUTHENTICATION) // execute this filter before others
public class TokenRequestFilter implements ContainerRequestFilter {

	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	public static final String QUERY_PARAMETER_TOKEN = "token";
	public static final String HEADER_AUTHORIZATION = "authorization";
	public static final String TOKEN_USER = "token";
	private static final int TOKEN_CACHE_LIFETIME = 10000; // ms

	private Map<String, Token> tokenCache = new HashMap<>();
	private boolean authenticationRequired = true;

	private AuthenticationClient authService;
	private boolean passwordRequired = true;

	private Timer tokenCacheTimer = new Timer("token cache cleanup", true);

	public TokenRequestFilter(AuthenticationClient authService) {
		this.authService = authService;
	}

	@Override
	public void filter(ContainerRequestContext requestContext)
			throws IOException {

		if ("OPTIONS".equals(requestContext.getMethod())) {

			// CORS preflight checks require unauthenticated OPTIONS
			return;
		}

		String authHeader = requestContext.getHeaderString(HEADER_AUTHORIZATION);
		// allow token to be sent also as a query parameter, because JS EventSource doesn't allow setting headers 
		String authParameter = requestContext.getUriInfo().getQueryParameters().getFirst(QUERY_PARAMETER_TOKEN);

		String password = getToken(authHeader, authParameter);

		if (password == null) { 
			if (passwordRequired) { 
				throw new NotAuthorizedException("password or token required");				
			} else if (!authenticationRequired) {				
				// this filter is configured to pass non-authenticated users through
				requestContext.setSecurityContext(new AuthSecurityContext(new AuthPrincipal(null, Role.UNAUTHENTICATED), requestContext.getSecurityContext()));
				return;
			}
		}

		try {
			// throws an exception if fails
			AuthPrincipal principal = tokenAuthentication(password);

			// login ok
			requestContext.setSecurityContext(
					new AuthSecurityContext(principal, requestContext.getSecurityContext()));
		} catch (ForbiddenException e) {
			// unable to check the user's token because auth didn't accept our own token
			throw new InternalServerErrorException("failed to check the token", e);
		} catch (NotFoundException e) {
			if (authenticationRequired) {
				throw new ForbiddenException(e);
			} else {
				// DatasetTokens have to be passed through
				requestContext.setSecurityContext(new AuthSecurityContext(
						new AuthPrincipal(null, password, new HashSet<String>()), requestContext.getSecurityContext()));
				return;				
			}
		}
	}

	public static String getToken(String authHeader, String authParameter) {
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

		synchronized (tokenCache) { // optimize reads to non-blocking
			if (tokenCache.containsKey(clientTokenKey)) {
				dbClientToken = tokenCache.get(clientTokenKey);
			} else {
				dbClientToken = authService.getDbToken(clientTokenKey);
				if (dbClientToken == null) {
					// auth responds with NotFoundException
					throw new NotFoundException("token not found");	
				}
				tokenCache.put(clientTokenKey, dbClientToken);
				tokenCacheTimer.schedule(new TimerTask() {

					@Override
					public void run() {
						synchronized (tokenCache) {
							tokenCache.remove(clientTokenKey); // clientTokeyKey is effectively final
						}
					}			
				}, TOKEN_CACHE_LIFETIME);
			}
		}

		if (dbClientToken.getValid().isBefore(LocalDateTime.now())) {
			// auth respons with NotFoundException
			throw new NotFoundException("token expired");
		}

		return new AuthPrincipal(dbClientToken.getUsername(), clientTokenKey, dbClientToken.getRoles());
	}

	public void authenticationRequired(boolean authenticationRequired, boolean passwordRequired) {
		this.authenticationRequired  = authenticationRequired;
		this.passwordRequired  = passwordRequired;
	}
}