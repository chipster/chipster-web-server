package fi.csc.chipster.auth.resource;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Priority;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.token.BasicAuthParser;
import fi.csc.chipster.rest.token.TokenRequestFilter;

/**
 * @author klemela
 *
 */
@Provider
@Priority(Priorities.AUTHENTICATION) // execute this filter before others
public class AuthenticationRequestFilter implements ContainerRequestFilter {
	
	private static final Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;

	private HashMap<String, String> users;

	public AuthenticationRequestFilter(HibernateUtil hibernate, Config config) throws IOException {
		this.hibernate = hibernate;
		
		users = new HashMap<>();
		users.put("client", "clientPassword");
		users.put("client2", "client2Password");
				
		for (String service : Config.services) {
			users.put(service, config.getPassword(service));
		}
	}

	@Override
	public void filter(ContainerRequestContext requestContext) throws IOException {    	

		if ("OPTIONS".equals(requestContext.getMethod())) {
			
			// CORS preflight checks require unauthenticated OPTIONS
			return;
		}
		String authHeader = requestContext.getHeaderString("authorization");
		
		if (authHeader == null) {
			throw new NotAuthorizedException("no authorization header found");
		}
		
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
	
	public AuthPrincipal tokenAuthentication(String tokenKey) {
		getHibernate().beginTransaction();
		UUID uuid;
		try {
			uuid = UUID.fromString(tokenKey);
		} catch (IllegalArgumentException e) {
			throw new ForbiddenException("tokenKey is not a valid UUID");
		}
		Token token = getHibernate().session().get(Token.class, uuid);
		if (token == null) {
			throw new ForbiddenException();
		}
		getHibernate().commit();
		
		return new AuthPrincipal(token.getUsername(), tokenKey, token.getRoles());
	}

	private AuthPrincipal passwordAuthentication(String username, String password) {
		
		// a small delay to slow down brute force attacks
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			logger.warn(e);
		}
				
		//TODO get users from JAAS or file or something
		//TODO make sure that external usernames matching Config.serivces are blocked
		
		if (!users.containsKey(username)) {
			throw new ForbiddenException();
		}

		if (!users.get(username).equals(password)) {
			throw new ForbiddenException();
		}
		
		// are these of any use, because the file broker authorization is anyway
		// based solely on the username?
		Map<String, String> usernameToRole = new HashMap<>();
		usernameToRole.put(Config.USERNAME_COMP, Role.COMP);
		usernameToRole.put(Config.USERNAME_FILE_BROKER, Role.FILE_BROKER);
		usernameToRole.put(Config.USERNAME_PROXY, Role.PROXY);
		usernameToRole.put(Config.USERNAME_SCHEDULER, Role.SCHEDULER);
		usernameToRole.put(Config.USERNAME_SERVICE_LOCATOR, Role.SERVICE_LOCATOR);
		usernameToRole.put(Config.USERNAME_SESSION_DB, Role.SESSION_DB);
		usernameToRole.put(Config.USERNAME_SESSION_WORKER, Role.SESSION_WORKER);
		
		String[] roles = new String[] { Role.PASSWORD, Role.CLIENT};		
		if (Config.services.contains(username)) {
			roles = new String[] { Role.PASSWORD, Role.SERVER, usernameToRole.get(username) };
		}
		
		return new AuthPrincipal(username, new HashSet<>(Arrays.asList(roles)));
	}
	
	private HibernateUtil getHibernate() {
		return hibernate;
	}
}