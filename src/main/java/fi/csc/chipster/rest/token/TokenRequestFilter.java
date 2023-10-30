package fi.csc.chipster.rest.token;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.ChipsterToken;
import fi.csc.chipster.auth.model.DatasetToken;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.SessionToken;
import fi.csc.chipster.auth.model.UserToken;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.auth.resource.AuthSecurityContext;
import fi.csc.chipster.auth.resource.AuthTokens;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.annotation.Priority;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Validate Chipster tokens
 * 
 * Validate Chipster tokens and parse claims to AuthPrincipal.
 * After this resource methods can limit their access by setting
 * @RolesAllowed annotation and by comparing the other information in 
 * AuthPrincipal. 
 * 
 * @author klemela
 *
 */
@Provider
@Priority(Priorities.AUTHENTICATION) // execute this filter before others
public class TokenRequestFilter implements ContainerRequestFilter {

	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	public static final String QUERY_PARAMETER_TOKEN = "token";
	public static final String HEADER_AUTHORIZATION = "authorization";
	public static final String TOKEN_USER = "token";

	private AuthenticationClient authService;

	private Set<String> allowedRoles = new HashSet<>();

	public TokenRequestFilter(AuthenticationClient authService) {
		this.authService = authService;
	}

	@Override
	public void filter(ContainerRequestContext requestContext) throws IOException {

		if ("OPTIONS".equals(requestContext.getMethod())) {

			// CORS preflight checks require unauthenticated OPTIONS
			return;
		}
		
//		logger.info(requestContext.getMethod() + " " + requestContext.getUriInfo().getRequestUri());

		String authHeader = requestContext.getHeaderString(HEADER_AUTHORIZATION);
		// allow token to be sent also as a query parameter, because JS EventSource
		// doesn't allow setting headers
		String authParameter = requestContext.getUriInfo().getQueryParameters().getFirst(QUERY_PARAMETER_TOKEN);

		String username = null;
		String password = null;
		
		if (authHeader != null) {
			BasicAuthParser parser = new BasicAuthParser(authHeader);			
			username = parser.getUsername();
			password = parser.getPassword();
		} else {
			username = TOKEN_USER;
			password = authParameter;
		}		

		if (password == null) {
			if (allowedRoles.contains(Role.UNAUTHENTICATED)) {
				// this filter is configured to pass non-authenticated users through
				requestContext.setSecurityContext(new AuthSecurityContext(new AuthPrincipal(Role.UNAUTHENTICATED),
						requestContext.getSecurityContext()));
				return;
				
			} else {
				throw new NotAuthorizedException("password or token required");
			}
		}
		
		if (!TOKEN_USER.equals(username)) {
			if (allowedRoles.contains(Role.PASSWORD)) {
				// throws an exception if fails
				AuthPrincipal principal = passwordAuthentication(username, password);

				// login ok
				requestContext.setSecurityContext(new AuthSecurityContext(principal, requestContext.getSecurityContext()));
				return;

			} else {
				throw new NotAuthorizedException("only tokens allowed");
			}
		}
		
		// throws if not valid
		Jws<Claims> jws = authService.validateTokenSignature(password);
		Claims jwsBody = jws.getPayload();
		
		// now we can trust that these claims were signed by auth
		
		ChipsterToken token = null;
		
		if (authService.isTokenClass(jwsBody, UserToken.class)) {
						
			UserToken userToken = AuthTokens.claimsToUserToken(jws.getPayload(), password);
			token = userToken;
			
		} else if (authService.isTokenClass(jwsBody, SessionToken.class)) {
			
			token = AuthTokens.claimsToSessionToken(jws.getPayload(), password);
			
		} else if (authService.isTokenClass(jwsBody, DatasetToken.class)) {
			
			token = AuthTokens.claimsToDatasetToken(jws.getPayload(), password);
			
		} else {
			throw new ForbiddenException("unknown token type");
		}
				
		// login ok
		AuthPrincipal principal = new AuthPrincipal(token, password);			
		requestContext.setSecurityContext(new AuthSecurityContext(principal, requestContext.getSecurityContext()));
	}

	private AuthPrincipal passwordAuthentication(String username, String password) {
		// throws if fails
		AuthenticationClient passwordAuthClient = new AuthenticationClient(
				this.authService.getAuth(), username, password, null, false);
		
		String tokenKey = passwordAuthClient.getToken();
		
		// we can trust the token that we got from auth, decoding is enough
		 UserToken parsedToken = AuthTokens.decodeUserToken(tokenKey);
		 
		 return new AuthPrincipal(parsedToken.getUsername(), tokenKey, parsedToken.getRoles());
	}

	/**
	 * Allow Role.UNAUTHENTICATED to pass this filter.
	 * By default those produce an authentication error.
	 *  
	 */
	public void addAllowedRole(String role) {
		
		this.allowedRoles.add(role);
	}
}