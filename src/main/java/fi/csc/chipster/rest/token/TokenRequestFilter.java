package fi.csc.chipster.rest.token;
import java.io.IOException;
import java.util.HashSet;

import javax.annotation.Priority;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.ParsedToken;
import fi.csc.chipster.auth.model.Role;
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

	private boolean authenticationRequired = true;

	private boolean passwordRequired = true;

	private AuthenticationClient authService;

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
			
			if (authenticationRequired) {
				throw new ForbiddenException(e);
			}
			
			HashSet<String> roles = new HashSet<String>() {{
				add(Role.SESSION_DB_TOKEN);
			}};
			// DatasetTokens have to be passed through
			requestContext.setSecurityContext(new AuthSecurityContext(
					new AuthPrincipal(null, password, roles), requestContext.getSecurityContext()));
			return;				
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

		ParsedToken validToken = authService.validate(clientTokenKey);		
		
		return new AuthPrincipal(validToken.getUsername(), clientTokenKey, validToken.getRoles());
	}

	public void authenticationRequired(boolean authenticationRequired, boolean passwordRequired) {
		this.authenticationRequired  = authenticationRequired;
		this.passwordRequired  = passwordRequired;
	}
}