package fi.csc.chipster.rest.token;
import java.io.IOException;
import java.security.PublicKey;
import java.util.HashSet;
import java.util.UUID;

import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.openssl.PEMException;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.auth.resource.AuthSecurityContext;
import fi.csc.chipster.auth.resource.Tokens;
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

	private PublicKey jwtPublicKey;

	public TokenRequestFilter(AuthenticationClient authService) {
		try {
			this.jwtPublicKey = authService.getJwtPublicKey();
		} catch (PEMException e) {
			throw new RuntimeException(e);
		}
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

		} catch (NotAuthorizedException e) {
			
			if (authenticationRequired) {
				throw new NotAuthorizedException(e.getMessage());
			}
			try {
				// DatasetTokens have to be passed through, but let's check that it's at least a valid UUID 
				UUID datasetToken = UUID.fromString(password);
			
				requestContext.setSecurityContext(new AuthSecurityContext(
						new AuthPrincipal(null, datasetToken.toString(), new HashSet<String>()), requestContext.getSecurityContext()));
				return;				
		
			} catch (IllegalArgumentException ex) {
				// probably this wasn't supposed to be a DatasetToken, send the original message
				throw new NotAuthorizedException(e.getMessage());
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

		Token validToken = Tokens.validate(clientTokenKey, this.jwtPublicKey);		
		
		return new AuthPrincipal(validToken.getUsername(), clientTokenKey, validToken.getRoles());
	}

	public void authenticationRequired(boolean authenticationRequired, boolean passwordRequired) {
		this.authenticationRequired  = authenticationRequired;
		this.passwordRequired  = passwordRequired;
	}
}