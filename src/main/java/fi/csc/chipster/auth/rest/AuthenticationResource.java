package fi.csc.chipster.auth.rest;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.rest.Hibernate;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.provider.NotAuthorizedException;

@Path("tokens")
public class AuthenticationResource {
		
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(AuthenticationResource.class.getName());
		
	// notifications
//    @GET
//    @Path("{id}/events")
//    @Produces(SseFeature.SERVER_SENT_EVENTS)
//    public EventOutput listenToBroadcast(@PathParam("id") String id, @QueryParam("username") String username) {
//		Hibernate.beginTransaction();
//		checkReadAuthorization(username, id);
//		Hibernate.commit();
//        return Events.getEventOutput();
//    }
	
    @POST
    @RolesAllowed(Role.CLIENT_CONSTANT)
    @Produces(MediaType.APPLICATION_JSON)
    public Response postCredentials(@HeaderParam("authorization") String authHeader) throws IOException {
    	
    	/* Usually Basic auth would be implemented in a ContainerRequestFilter 
    	 * to protect all the resources. Here Basic auth is used only for this 
    	 * one resource, so we can implement it directly in here. 
    	 */
    	
    	// curl -i -H "Content-Type: application/json" --user client:clientpassword -X POST http://localhost:8081/auth/tokens
    	
    	if (authHeader == null) {
    		throw new NotAuthorizedException("no authorization header");
    	}
    	
    	String[] credentials = BasicAuth.decode(authHeader);
    	String username = credentials[0];
    	String password = credentials[1];
    	    
    	if (credentials == null || credentials.length != 2) {
    		throw new NotAuthorizedException("incorrect format for authorization header");
    	}
    	if (username == null || password == null) {
    		throw new NotAuthorizedException("username or passwod is null");
    	}
    	
		//TODO get from JAAS or file or something
		Map<String, String> users = new HashMap<>();
		users.put("client", "clientpassword");
		users.put("sessionStorage", "sessionStoragePassword");
								
		if (users.containsKey(username) && users.get(username).equals(password)) {
			return Response.ok(createToken(username)).build();
		}
		
		throw new ForbiddenException();
    }
        
	private Token createToken(String username) {
		
		cleanUp();
		
		//FIXME has to be cryptographically secure
		String tokenString = RestUtils.createId();
		//LocalDateTime valid = LocalDateTime.now().plusMonths(1);
		LocalDateTime valid = LocalDateTime.now().minusMonths(1);

		//Token token = new Token(username, role, tokenString, date);
		Token token = new Token(username, Role.CLIENT, tokenString, valid);
		Hibernate.beginTransaction();
		Hibernate.session().save(token);
		Hibernate.commit();
		
		return token;
	}

	/**
	 * Remove all expired tokens
	 */
	private void cleanUp() {
		
		Timestamp timestamp = Timestamp.valueOf(LocalDateTime.now());

		Hibernate.beginTransaction();
		Hibernate.session()
			.createQuery("delete from Token where valid < :timestamp")
			.setParameter("timestamp", timestamp);
		Hibernate.commit();
	}

	@GET
	@Path("{token}")
	//@Consumes(MediaTye.APPLICATION_JSON)
	@RolesAllowed(Role.SESSION_STORAGE_CONSTANT)
    @Produces(MediaType.APPLICATION_JSON)
    //public Response checkToken(Token requestToken) {
	public Response checkToken(@PathParam("token") String token) {
		
		//FIXME allow only for server roles
		
//		if (requestToken == null || requestToken.getTokenKey() == null) {
//			throw new NotFoundException();
//		}
//
//		Hibernate.beginTransaction();		
//		Token dbToken = (Token) Hibernate.session().get(Token.class, requestToken.getTokenKey());
//		Hibernate.commit();
		
		if (token == null) {
			throw new NotFoundException();
		}

		Hibernate.beginTransaction();		
		Token dbToken = (Token) Hibernate.session().get(Token.class, token);
		Hibernate.commit();
	
		if (dbToken == null) {
			throw new NotFoundException();
		}
		
		//if (dbToken.getValid().after(new Date())) {
		if (dbToken.getValid().isAfter(LocalDateTime.now())) {
			return Response.ok(dbToken).build();
		} else {
			throw new ForbiddenException("token expired");
		}				
    }	

	@DELETE
	@Consumes(MediaType.APPLICATION_JSON)
    public Response delete(Token requestToken) {

		if (requestToken.getTokenKey() == null) {
			throw new NotFoundException();
		}

		Hibernate.beginTransaction();		
		Token dbToken = (Token) Hibernate.session().get(Token.class, requestToken.getTokenKey());
		Hibernate.session().delete(dbToken);
		Hibernate.commit();
	
		if (dbToken == null) {
			throw new NotFoundException();
		}
		
		return Response.noContent().build();
    }
}
