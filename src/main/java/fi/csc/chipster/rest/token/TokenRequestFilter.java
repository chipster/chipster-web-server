package fi.csc.chipster.rest.token;
import java.io.IOException;

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
	private String serverTokenKey;
	//FIXME read credentials from config
	private WebTarget authTarget = new AuthenticatedTarget("sessionStorage", "sessionStoragePassword").target(new AuthenticationService().getBaseUri());

	@Override
	public void filter(ContainerRequestContext requestContext)
			throws IOException {
		
		BasicAuthParser parser = new BasicAuthParser(requestContext.getHeaderString("authorization"));
		
		if (!TOKEN_USER.equals(parser.getUsername())) {
			throw new NotAuthorizedException("only tokens allowed");
		}

		// throws an exception if fails
		AuthPrincipal principal = tokenAuthentication(parser.getPassword());
		
		// login ok
		requestContext.setSecurityContext(
				new AuthSecurityContext(principal, requestContext.getSecurityContext()));		
	}

	public AuthPrincipal tokenAuthentication(String clientTokenKey) {
        
		
		Token dbClientToken = getDbToken(clientTokenKey);
    	
        if (dbClientToken == null) {
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