package fi.csc.chipster.auth;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthAdminResource;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestMethods;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.sessiondb.RestException;
import jakarta.ws.rs.client.WebTarget;

public class AuthenticationAdminClient {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private ServiceLocatorClient serviceLocator;
	private CredentialsProvider credentials;

	private String authAdminUri;

	/**
	 * @param serviceLocator
	 * @param credentials
	 * @param role set to Role.CLIENT to use public addresses, anything else, e.g. Role.SERVER to use internal addresses 
	 */
	public AuthenticationAdminClient(ServiceLocatorClient serviceLocator, CredentialsProvider credentials) {
		this.serviceLocator = serviceLocator;
		this.credentials = credentials;
		
		// get session-db, remove session-db-events
		List<Service> internalServices = serviceLocator.getInternalServices(Role.AUTH).stream()
				.filter(s -> Role.AUTH.equals(s.getRole()))
				.collect(Collectors.toList());
				
		authAdminUri = internalServices.get(0).getAdminUri();
	}	
	
	private WebTarget getAuthAdminTarget() {
		
		WebTarget target = null;
		if (credentials == null) {
			target = AuthenticationClient.getClient().target(authAdminUri); // for testing
		} else {
			target = AuthenticationClient.getClient(credentials.getUsername(), credentials.getPassword(), true).target(authAdminUri);
		}
		
		return target.path("admin");
	}
		
	
	// targets
	
	private WebTarget getUsersTarget() {
		return getAuthAdminTarget().path(AuthAdminResource.PATH_USERS);
	}

	private WebTarget getUsersTarget(String... username) {
		return getUsersTarget().queryParam("userId", (Object[]) username);
	}

	
	// actions
	
	public String deleteUser(String... username) throws RestException {
		return RestMethods.deleteJson(getUsersTarget(username));
	}
}
