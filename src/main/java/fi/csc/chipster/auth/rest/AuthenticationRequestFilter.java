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
import fi.csc.chipster.rest.token.BasicAuthParser;
import fi.csc.chipster.rest.token.TokenRequestFilter;

@Provider
@Priority(Priorities.AUTHENTICATION) // execute this filter before others
public class AuthenticationRequestFilter implements ContainerRequestFilter {
	
	@Override
	public void filter(ContainerRequestContext requestContext) throws IOException {    	

		BasicAuthParser parser = new BasicAuthParser(requestContext.getHeaderString("authorization"));
		
		AuthPrincipal principal = null;
		
		if (TokenRequestFilter.TOKEN_USER.equals(parser.getUsername())) {
			// throws an exception if fails
			principal = tokenAuthentication(parser.getPassword());
		} else {
			// throws an exception if fails
			principal = passwordAuthentication(parser.getUsername(), parser.getPassword());
		}

		// login ok
		AuthSecurityContext sc = new AuthSecurityContext(principal, requestContext.getSecurityContext());
		requestContext.setSecurityContext(sc);		
	}
	
	public static AuthPrincipal tokenAuthentication(String tokenKey) {
		Hibernate.beginTransaction();
		Token token = (Token) Hibernate.session().get(Token.class, tokenKey);
		if (token == null) {
			throw new ForbiddenException();
		}
		
		return new AuthPrincipal(token.getUsername(), tokenKey, token.getRoles());
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