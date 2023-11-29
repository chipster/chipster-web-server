package fi.csc.chipster.auth.resource;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;

import fi.csc.chipster.auth.jaas.JaasAuthenticationProvider;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.auth.model.UserToken;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.exception.TooManyRequestsException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.rest.token.BasicAuthParser;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.sessionworker.RequestThrottle;
import jakarta.annotation.Priority;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * @author klemela
 *
 */
@Provider
@Priority(Priorities.AUTHENTICATION) // execute this filter before others
public class AuthenticationRequestFilter implements ContainerRequestFilter {



	private static final Logger logger = LogManager.getLogger();

	private static final String CONF_PASSWORD_THROTTLE_PERIOD = "auth-password-throttle-period";
	private static final String CONF_PASSWORD_THROTTLE_REQEUST_COUNT = "auth-password-throttle-request-count";	
	private static final String CONF_SERVER_PASSWORD_THROTTLE_PERIOD = "auth-server-password-throttle-period";
	private static final String CONF_SERVER_PASSWORD_THROTTLE_REQEUST_COUNT = "auth-server-password-throttle-request-count";	
	private static final String CONF_SERVER_PASSWORD_THROTTLE_LIST = "auth-server-password-throttle-list";

	private HibernateUtil hibernate;
	
	@SuppressWarnings("unused")
	private Config config;
	private UserTable userTable;

	private Map<String, String> serviceAccounts;
	private Set<String> adminAccounts;

	private final String jaasPrefix;

	private JaasAuthenticationProvider authenticationProvider;

	private HashMap<String, String> monitoringAccounts;

	private AuthTokens tokenTable;

	private RequestThrottle passwordThrottle;

	private HashSet<String> serverThrottleList;

	private RequestThrottle serverPasswordThrottle;

	public AuthenticationRequestFilter(HibernateUtil hibernate, Config config, UserTable userTable, AuthTokens tokenTable, JaasAuthenticationProvider jaasAuthProvider) throws IOException {
		this.hibernate = hibernate;
		this.config = config;
		this.userTable = userTable;
		this.tokenTable = tokenTable;
		this.authenticationProvider = jaasAuthProvider;

		serviceAccounts = config.getServicePasswords();		
		adminAccounts = config.getAdminAccounts();
		jaasPrefix = config.getString(Config.KEY_AUTH_JAAS_PREFIX);
				
		String monitoringPassword = config.getString(Config.KEY_MONITORING_PASSWORD);
		if (config.getDefault(Config.KEY_MONITORING_PASSWORD).equals(monitoringPassword)) {
			logger.warn("default password for username " + Role.MONITORING);
		}
		
		monitoringAccounts = new HashMap<String, String>() {{ put(Role.MONITORING, monitoringPassword); }};
		
		/* Each replica counts its own request counts
		 * 
		 * This is probably fine for small number of replicas. With dozens of replicas
		 * you would need a centralized storage for this.
		 */
		int throttlePeriod = config.getInt(CONF_PASSWORD_THROTTLE_PERIOD);
		int throttleRequestCount = config.getInt(CONF_PASSWORD_THROTTLE_REQEUST_COUNT);
		int serverThrottlePeriod = config.getInt(CONF_SERVER_PASSWORD_THROTTLE_PERIOD);
		int serverThrottleRequestCount = config.getInt(CONF_SERVER_PASSWORD_THROTTLE_REQEUST_COUNT);
		serverThrottleList = new HashSet<String>(Arrays.asList(config.getString(CONF_SERVER_PASSWORD_THROTTLE_LIST).split(" ")));
			
		this.passwordThrottle = new RequestThrottle(Duration.ofSeconds(throttlePeriod), throttleRequestCount);
		this.serverPasswordThrottle = new RequestThrottle(Duration.ofSeconds(serverThrottlePeriod), serverThrottleRequestCount);
	}

	@Override
	public void filter(ContainerRequestContext requestContext) throws IOException {    	
		if ("OPTIONS".equals(requestContext.getMethod())) {

			// CORS preflight checks require unauthenticated OPTIONS
			return;
		}
		String authHeader = requestContext.getHeaderString("authorization");

		if (authHeader == null) {
			// OidcResource needs unauthenticated access
			requestContext.setSecurityContext(new AuthSecurityContext(new AuthPrincipal(Role.UNAUTHENTICATED), requestContext.getSecurityContext()));
			return;
		}

		if (authHeader.startsWith("Basic ") || authHeader.startsWith("basic ")) {
			BasicAuthParser parser = new BasicAuthParser(authHeader);
	
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
		} else {
			throw new NotAuthorizedException("unknown authorization header type");
		}
	}

	public AuthPrincipal tokenAuthentication(String jwsString) {
		
		// throws if fails
		UserToken token = tokenTable.validateUserToken(jwsString);				

		return new AuthPrincipal(token.getUsername(), jwsString, token.getRoles());
	}

	private AuthPrincipal passwordAuthentication(String username, String password) {		

		/* 
		 * Slow down brute force attacks
		 * 
		 * It could be possible to exhaust memory by trying different usernames. 
		 * Maybe add another throttle based on IP address. Finally,
		 * respond with CONF_PASSWORD_THROTTLE_PERIOD to everyone after the list size grows 
		 * really large.  
		 */				
		Duration retryAfter = null;
		
		// throttle less server accounts and unit test accounts 
		if (serverThrottleList.contains(username) || serviceAccounts.containsKey(username)) {
			// we can trust that servers have proper passwords
			retryAfter = serverPasswordThrottle.throttle(username);
		} else {			
			retryAfter = passwordThrottle.throttle(username);
		}

		if (!retryAfter.isZero()) {
			// + 1 to round up
			long ceilSeconds = retryAfter.getSeconds() + 1;

			logger.warn("throttling password requests for username '" + username + "'");
			throw new TooManyRequestsException(ceilSeconds);			
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
		
		if (monitoringAccounts.containsKey(username)) { 
			if (monitoringAccounts.get(username).equals(password)) {		
				// authenticate with username/password ok
				return new AuthPrincipal(username, getRoles(username));
			}
			// don't let other providers to authenticate internal usernames
			throw new ForbiddenException("wrong password");	
		}
		
		// allow both plain username "jdoe" or userId "jaas/jdoe" 
		String jaasUsername;		
		try {
			// throws if username is not a userId
			UserId userId = new UserId(username);			
			if (userId.getAuth().equals(jaasPrefix)) {
				// jaas userId (e.g. "jaas/jdoe"), login without the prefix
				jaasUsername = userId.getUsername();
			} else {
				// userId, but not from jaas (e.g. "sso/jdoe"), no point to try for jaas
				jaasUsername = null;
			}
		} catch (IllegalArgumentException e) {
			// not a userId but only a username (e.g. "jdoe"), but that's fine
			jaasUsername = username;
		}
		
		if (jaasUsername != null && authenticationProvider.authenticate(jaasUsername, password.toCharArray())) {
		    
		    UserId userId = new UserId(jaasPrefix, jaasUsername);
		    
			addOrUpdateUser(userId);
			// authenticate with username/password ok
			return new AuthPrincipal(userId.toUserIdString(), getRoles(username));
		}
		throw new ForbiddenException("wrong username or password");
	}

	private void addOrUpdateUser(UserId userId) {	    
	    
		hibernate.runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(Session hibernateSession) {
				userTable.addOrUpdateFromLogin(userId, null, null, userId.getUsername(), hibernateSession);
				return null;
			}
		});
	}

	public HashSet<String> getRoles(String username) {

		HashSet<String> roles = new HashSet<>();
		roles.add(Role.PASSWORD);
		
		if (serviceAccounts.keySet().contains(username)) {
			
			// minimal access rights if SingleShotComp service account is used someday
			if (!Role.SINGLE_SHOT_COMP.equals(username)) {
				roles.add(Role.SERVER);
			}
			roles.add(username);
			
		} else if (monitoringAccounts.containsKey(username)) {
			roles.add(Role.MONITORING);
			
		} else {
			roles.add(Role.CLIENT);
			
			if (adminAccounts.contains(username)) {
				roles.add(Role.ADMIN);
			}
		}

		return roles;
	}
}