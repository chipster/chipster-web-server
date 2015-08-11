package fi.csc.chipster.auth.rest;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.provider.NotAuthorizedException;

@Provider
public class AuthorizationRequestFilter implements ContainerRequestFilter {

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

		//TODO get from JAAS or file or something
		Map<String, String> users = new HashMap<>();
		users.put("client", "clientpassword");
		users.put("sessionStorage", "sessionStoragePassword");

		if (!users.containsKey(username)) {
			throw new ForbiddenException();
		}

		if (!users.get(username).equals(password)) {
			throw new ForbiddenException();
		}

		if (requestContext.getSecurityContext() == null) {
			throw new ForbiddenException();
		}
		// login ok
			
		Role role = Role.CLIENT;
		if ("sessionStorage".equals(username)) {
			role = Role.SESSION_STORAGE;
		}
		requestContext.setSecurityContext(new AuthSecurityContext(username, requestContext.getSecurityContext(), role));		
	}
}