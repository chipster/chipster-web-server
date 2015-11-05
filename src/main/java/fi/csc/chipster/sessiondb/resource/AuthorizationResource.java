package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Authorization;
import fi.csc.chipster.sessiondb.model.Session;

@Path("/")
public class AuthorizationResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;

	public AuthorizationResource(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}
	
    @GET
    @Path("authorizations")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed(Role.FILE_BROKER)
    @Transaction
    public Response get(@QueryParam("session-id") UUID sessionId, @QueryParam("username") String username, @QueryParam("read-write") Boolean requireReadWrite, @Context SecurityContext sc) throws IOException {

    	if (sessionId == null || username == null || requireReadWrite == null) {
    		throw new BadRequestException("session-id, username or read-write query parameter is null");
    	}
    	
    	// this query wouldn't be need, if it was possible to query Authorization
    	// table directly with sesssionId
    	Session session = getSession(sessionId);
    	
    	if (session == null) {
    		throw new NotFoundException("session not found");
    	}
    	
    	Authorization authorization = getAuthorization(username, session);
    	    	
    	if (authorization == null) {
    		throw new NotFoundException("session not authorized for user " + username);
    	}
    	
    	if (requireReadWrite && !authorization.isReadWrite()) {
    		// not really 401 (Unauthorized) or 403 (Forbidden), because the server authenticated correctly and is authorized to do this
    		throw new NotFoundException("no read-write authorization for user " + username);
    	}
    	 
    	return Response.ok().build();    	
    }
        	
	public Session getSessionForReading(SecurityContext sc, UUID sessionId) {
		Authorization auth = checkAuthorization(sc, sessionId, false);
		return auth.getSession();
	}
	
	public Session getSessionForWriting(SecurityContext sc, UUID sessionId) {
		Authorization auth = checkAuthorization(sc, sessionId, true);
		return auth.getSession();
	}
	
	public Authorization getReadAuthorization(SecurityContext sc, UUID sessionId) {
    	return checkAuthorization(sc, sessionId, false);
    }
	public Authorization getWriteAuthorization(SecurityContext sc, UUID sessionId) {
    	return checkAuthorization(sc, sessionId, true);
    }
    
	public Authorization checkAuthorization(SecurityContext sc, UUID sessionId, boolean requireReadWrite) {

		String username = sc.getUserPrincipal().getName();
		if(username == null) {
			throw new NotAuthorizedException("username is null");
		}
		Session session = hibernate.session().get(Session.class, sessionId);
		
		if (session == null) {
			throw new NotFoundException("session not found");
		}
		
		if (sc.isUserInRole(Role.SCHEDULER) || sc.isUserInRole(Role.COMP) || sc.isUserInRole(Role.FILE_BROKER)) {		
			return new Authorization(username, session, true);
		}
		
		Authorization auth = getAuthorization(username, session);
		
		if (auth == null) {
			throw new NotAuthorizedException("access denied");
		}
		
		if (requireReadWrite) {
			if (!auth.isReadWrite()) {
				throw new ForbiddenException("read-write access denied");
			}
		}
		return auth;
	}

	@SuppressWarnings("unchecked")
	public List<Authorization> getAuthorizations(String username) {		
		return hibernate.session()
				.createQuery("from Authorization where username=:username")
				.setParameter("username", username)
				.list();
	}
	
	public Session getSession(UUID sessionId) {
		return hibernate.session().get(Session.class, sessionId);
	}
	
	public Authorization getAuthorization(String username, Session session) {			
		
		return (Authorization) hibernate.session()
				.createQuery("from Authorization where username=:username and session=:session")
				.setParameter("username", username)
				.setParameter("session", session)
				.uniqueResult();
	}
}
