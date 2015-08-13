package fi.csc.chipster.auth.rest;

import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.DELETE;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.rest.Hibernate;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.provider.NotAuthorizedException;

@Path("tokens")
public class AuthenticationResource {
		
	private static final String TOKEN_HEADER = "chipster-token";

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
    @RolesAllowed(Role.PASSWORD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createToken(@Context SecurityContext sc) {
    	
    	// curl -i -H "Content-Type: application/json" --user client:clientpassword -X POST http://localhost:8081/auth/token
    	
    	cleanUp();
    	
    	AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
    	
    	String username = sc.getUserPrincipal().getName();
    	    
    	if (username == null) {
    		// RolesAllowed prevents this
    		throw new NotAuthorizedException("username is null");
    	}
    	
		//FIXME has to be cryptographically secure
		String tokenString = RestUtils.createId();
		LocalDateTime valid = LocalDateTime.now().plusMonths(1);

		String rolesJson = RestUtils.asJson(principal.getRoles());
		
		Token token = new Token(username, tokenString, valid, rolesJson);
		getHibernate().beginTransaction();
		getHibernate().session().save(token);
		getHibernate().commit();

    									
		return Response.ok(token).build();
    }

	/**
	 * Remove all expired tokens
	 */
	private void cleanUp() {
		
		getHibernate().beginTransaction();
		int rows = getHibernate().session()
			.createQuery("delete from Token where valid < :timestamp")
			.setParameter("timestamp", LocalDateTime.now()).executeUpdate();
		
		if (rows > 0) {
			logger.log(Level.INFO, "deleted " + rows + " expired token(s)");
		}
		getHibernate().commit();
	}

	@GET
	@RolesAllowed(Role.SERVER)
    @Produces(MediaType.APPLICATION_JSON)
	public Response checkToken(@HeaderParam(TOKEN_HEADER) String requestToken) {
		
		if (requestToken == null) {
			throw new NotFoundException("chipster-token header is null");
		}

		getHibernate().beginTransaction();		
		Token dbToken = (Token) getHibernate().session().get(Token.class, requestToken);
		getHibernate().commit();
	
		if (dbToken == null) {
			throw new NotFoundException();
		}
		
		if (dbToken.getValid().isAfter(LocalDateTime.now())) {
			return Response.ok(dbToken).build();
		} else {
			throw new ForbiddenException("token expired");
		}				
    }	

	@DELETE
	@RolesAllowed(Role.CLIENT)
    public Response delete(@Context SecurityContext sc) {

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
		
		getHibernate().beginTransaction();		
		Token dbToken = (Token) getHibernate().session().get(Token.class, principal.getTokenKey());
		if (dbToken == null) {
			throw new NotFoundException();
		}
		getHibernate().session().delete(dbToken);
		getHibernate().commit();
			
		return Response.noContent().build();
    }
	
	private static Hibernate getHibernate() {
		return AuthenticationService.getHibernate();
	}
}
