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
import fi.csc.microarray.auth.JaasAuthenticationProvider;
import fi.csc.microarray.config.ConfigurationLoader.IllegalConfigurationException;

/**
 * @author klemela
 *
 */
@Provider
@Priority(Priorities.AUTHENTICATION) // execute this filter before others
public class AuthenticationRequestFilter implements ContainerRequestFilter {
	
	private static final Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;

	private HashMap<String, String> serviceAccounts;

	private JaasAuthenticationProvider authenticationProvider;

	public AuthenticationRequestFilter(HibernateUtil hibernate, Config config) throws IOException, IllegalConfigurationException {
		this.hibernate = hibernate;
		
		serviceAccounts = new HashMap<>();	
				
		for (String service : Config.services) {
			serviceAccounts.put(service, config.getPassword(service));
		}
		
		authenticationProvider = new JaasAuthenticationProvider(false);
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
		
		// check that there is no extra white space in the username, because if authenticationProvider accepts it,
		// it would create a new user in Chipster
		if (!username.trim().equals(username)) {
			throw new ForbiddenException("white space in username");
		}
		
		if (serviceAccounts.containsKey(username)) { 
			if (serviceAccounts.get(username).equals(password)) {		
			// authenticate with username/password ok
				return new AuthPrincipal(username, getRoles(username));
			}
			// don't let other providers to authenticate internal usernames
			throw new ForbiddenException("wrong password");	
		}
		if (authenticationProvider.authenticate(username, password.toCharArray())) {
			// authenticate with username/password ok
			return new AuthPrincipal(username, getRoles(username));
		}
		throw new ForbiddenException("wrong username or password");
	}
	
	public HashSet<String> getRoles(String username) {
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
		
		String[] roles;
		if (Config.services.contains(username)) {
			roles = new String[] { Role.PASSWORD, Role.SERVER, usernameToRole.get(username) };
		} else {
			roles = new String[] { Role.PASSWORD, Role.CLIENT};
		}
		
		return new HashSet<>(Arrays.asList(roles));
	}

	private HibernateUtil getHibernate() {
		return hibernate;
	}
}