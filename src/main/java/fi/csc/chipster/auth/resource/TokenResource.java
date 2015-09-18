package fi.csc.chipster.auth.resource;

import java.time.LocalDateTime;
import java.util.HashSet;
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
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path("tokens")
public class TokenResource {
	
	public static final String TOKENS = "tokens";
	
	private static final String TOKEN_HEADER = "chipster-token";

	private static Logger logger = Logger.getLogger(TokenResource.class.getName());

	private HibernateUtil hibernate;
		
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
	
    public TokenResource(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}

	@POST
    @RolesAllowed(Role.PASSWORD)
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response createToken(@Context SecurityContext sc) {
    	
    	// curl -i -H "Content-Type: application/json" --user client:clientPassword -X POST http://localhost:8081/auth/tokens
    	
    	// this shouldn't be executed on every request
    	cleanUp();
    	
    	AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
    	
    	String username = sc.getUserPrincipal().getName();
    	    
    	if (username == null) {
    		// RolesAllowed prevents this
    		throw new NotAuthorizedException("username is null");
    	}

    	Token token = createToken(username, principal.getRoles());

		getHibernate().session().save(token);
    									
		return Response.ok(token).build();
    }

	public Token createToken(String username, HashSet<String> roles) {
		//FIXME has to be cryptographically secure
		String tokenString = RestUtils.createId();
		LocalDateTime valid = LocalDateTime.now().plusMonths(1);

		String rolesJson = RestUtils.asJson(roles);
		
		return new Token(username, tokenString, valid, rolesJson);
	}

	/**
	 * Remove all expired tokens
	 */
	private void cleanUp() {
		
		int rows = getHibernate().session()
			.createQuery("delete from Token where valid < :timestamp")
			.setParameter("timestamp", LocalDateTime.now()).executeUpdate();
		
		if (rows > 0) {
			logger.log(Level.INFO, "deleted " + rows + " expired token(s)");
		}
	}

	@GET
	@RolesAllowed(Role.SERVER)
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response checkToken(@HeaderParam(TOKEN_HEADER) String requestToken) {
		
		if (requestToken == null) {
			throw new NotFoundException("chipster-token header is null");
		}
		
		Token dbToken = (Token) getHibernate().session().get(Token.class, requestToken);
	
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
	@Transaction
    public Response delete(@Context SecurityContext sc) {

		AuthPrincipal principal = (AuthPrincipal) sc.getUserPrincipal();
			
		Token dbToken = (Token) getHibernate().session().get(Token.class, principal.getTokenKey());
		if (dbToken == null) {
			throw new NotFoundException();
		}
		getHibernate().session().delete(dbToken);
			
		return Response.noContent().build();
    }
	
	private HibernateUtil getHibernate() {
		return hibernate;
	}
}
