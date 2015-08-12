package fi.csc.chipster.auth.rest;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import javax.annotation.Priority;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.rest.Hibernate;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.provider.NotAuthorizedException;

@Provider
@Priority(Priorities.AUTHENTICATION) // execute this filter before others
public class AuthorizationRequestFilter implements ContainerRequestFilter {
	
	public static final String TOKEN_USER = "token";

	@Override
	public void filter(ContainerRequestContext requestContext)
			throws IOException {    	

		//Get the authentification passed in HTTP headers parameters
		String auth = requestContext.getHeaderString("authorization");
		
		//If the user does not have the right (does not provide any HTTP Basic Auth)
		if(auth == null){
			//requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).entity("username or password missing").build());
			throw new NotAuthorizedException("no authorization header");
		}

		//lap : loginAndPassword
		String[] credentials = BasicAuth.decode(auth);

		//If login or password fail
		if(credentials == null || credentials.length != 2){
			throw new NotAuthorizedException("username or password missing");
		}		

		String username = credentials[0];
		String password = credentials[1];
		
		AuthPrincipal principal = null;
		
		if (TOKEN_USER.equals(username)) {
			// throws an exception if fails
			principal = tokenAuthentication(username, password);
		} else {
			// throws an exception if fails
			principal = passwordAuthentication(username, password);
		}

		if (requestContext.getSecurityContext() == null) {
			throw new ForbiddenException();
		}
		// login ok
		AuthSecurityContext sc = new AuthSecurityContext(principal, requestContext.getSecurityContext());
		requestContext.setSecurityContext(sc);		
	}

	private AuthPrincipal tokenAuthentication(String username, String tokenKey) {
		Hibernate.beginTransaction();
		Token token = (Token) Hibernate.session().get(Token.class, tokenKey);
		if (token == null) {
			throw new ForbiddenException();
		}
		
		return new AuthPrincipal(username, tokenKey, token.getRoles());
	}

	private AuthPrincipal passwordAuthentication(String username, String password) {
		//TODO get from JAAS or file or something
		Map<String, String> users = new HashMap<>();
		users.put("client", "clientPassword");
		users.put("sessionStorage", "sessionStoragePassword");

		if (!users.containsKey(username)) {
			throw new ForbiddenException();
		}

		if (!users.get(username).equals(password)) {
			throw new ForbiddenException();
		}
		
		String[] roles = new String[] { Role.PASSWORD, Role.CLIENT};
		if ("sessionStorage".equals(username)) {
			roles = new String[] { Role.PASSWORD, Role.SESSION_STORAGE, Role.SERVER };
		}
		
		return new AuthPrincipal(username, new HashSet<>(Arrays.asList(roles)));
	}
}