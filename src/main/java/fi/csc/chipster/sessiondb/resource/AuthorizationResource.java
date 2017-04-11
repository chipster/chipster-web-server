package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.StreamingOutput;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Query;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Authorization;
import fi.csc.chipster.sessiondb.model.Session;

@Path("authorizations")
public class AuthorizationResource {	
	
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;

	private Config config;

	private Set<String> servicesAccounts;

	public AuthorizationResource(HibernateUtil hibernate) {
		this.hibernate = hibernate;
		this.config = new Config();
		this.servicesAccounts = config.getServicePasswords().keySet();
	}
	
	@GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed(Role.SESSION_DB)
    @Transaction
    public Response get(@PathParam("id") UUID authorizationId, @Context SecurityContext sc) throws IOException {
    	    
		Authorization result = getAuthorization(authorizationId, hibernate.session());
    	if (result == null) {
    		throw new NotFoundException();
    	}	
    	return Response.ok(result).build();    	
    }
	
	public Authorization getAuthorization(UUID authorizationId, org.hibernate.Session hibernateSession) {
		return hibernateSession.get(Authorization.class, authorizationId);
	}
	
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response get(@QueryParam("session-id") UUID sessionId, @QueryParam("username") String username, @QueryParam("read-write") Boolean requireReadWrite, @Context SecurityContext sc) throws IOException {
    	
    	// session db is allowed to get all
    	if (sessionId == null && username == null && requireReadWrite == null) {
    		if (sc.isUserInRole(Role.SESSION_DB)) {
    			return getAuthorizations();
    		}
    	}
    	
    	// file broker is allowed to make specific queries
    	if (!sc.isUserInRole(Role.FILE_BROKER)) {
    		throw new ForbiddenException();
    	}
    	
    	if (sessionId == null || username == null || requireReadWrite == null) {
    		throw new BadRequestException("session-id, username or read-write query parameter is null");
    	}
    	
    	// this query wouldn't be need, if it was possible to query Authorization
    	// table directly with sesssionId
    	Session session = getSession(sessionId);
    	
    	if (session == null) {
    		throw new NotFoundException("session not found");
    	}
    	
    	Authorization authorization = getAuthorization(username, session, hibernate.session());
    	    	
    	if (authorization == null) {
    		throw new NotFoundException("session not authorized for user " + username);
    	}
    	
    	if (requireReadWrite && !authorization.isReadWrite()) {
    		// not really 401 (Unauthorized) or 403 (Forbidden), because the server authenticated correctly and is authorized to do this
    		throw new NotFoundException("no read-write authorization for user " + username);
    	}
    	 
    	return Response.ok().build();    	
    }
    
    public Authorization checkAuthorization(String username, UUID sessionId, boolean requireReadWrite) {
    	return checkAuthorization(username, sessionId, requireReadWrite, hibernate.session());
    }
    
	public Authorization checkAuthorization(String username, UUID sessionId, boolean requireReadWrite, org.hibernate.Session hibernateSession) {

		if(username == null) {
			throw new NotAuthorizedException("username is null");
		}
		Session session = hibernateSession.get(Session.class, sessionId);
		
		if (session == null) {
			throw new NotFoundException("session not found");
		}
		
		Authorization auth = getAuthorization(username, session, hibernateSession);
		
		if (auth == null) {
			throw new ForbiddenException("access denied");
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
	
	/**
	 * Stream the whole table as a json array
	 * 
	 * @return
	 */
	public Response getAuthorizations() {
	
		StreamingOutput stream = new StreamingOutput() {
	    	@Override
	    	public void write(final OutputStream output) {
	    		hibernate.runInTransaction(new HibernateRunnable<Void>() {
	    			@Override
	    			public Void run(org.hibernate.Session hibernateSession) {	    				try {
	    					Query query = hibernateSession.createQuery("from Authorization");
	    					query.setReadOnly(true);

	    					ScrollableResults results = query.scroll(ScrollMode.FORWARD_ONLY);

	    					JsonGenerator jg = RestUtils.getObjectMapper().getFactory().createGenerator(output, JsonEncoding.UTF8);
	    					jg.writeStartArray();

	    					// iterate over results
	    					while (results.next()) {
	    						// process row then release reference
	    						Authorization row = (Authorization) results.get()[0];
	    						jg.writeObject(row);
	    						jg.flush();
	    						// you may need to flush every now and then
    							//hibernate.session().flush();
	    					}
	    					results.close();

	    					jg.writeEndArray();
	    					jg.flush();
	    					jg.close();
	    				} catch (IOException e) {
	    					e.printStackTrace();
	    					logger.error(e);
	    					// rollback the transaction
	    					throw new RuntimeException(e);
	    				}
	    				return null;
	    			}					
	    		});
	    	}
	    };
 
        return Response.ok().entity( stream ).type( MediaType.APPLICATION_JSON ).build()    ;
	}
	
	public Session getSession(UUID sessionId) {
		return hibernate.session().get(Session.class, sessionId);
	}
	
	public Authorization getAuthorization(String username, Session session, org.hibernate.Session hibernateSession) {
		
		if (servicesAccounts.contains(username)) {
			return new Authorization(username, session, true);
		}
		
		return (Authorization) hibernateSession
				.createQuery("from Authorization where username=:username and session=:session")
				.setParameter("username", username)
				.setParameter("session", session)
				.uniqueResult();
	}

	/**
	 * Persist the authorization and its session in the DB
	 * 
	 * @param auth
	 * @param hibernateSession 
	 */
	public void save(Authorization auth, org.hibernate.Session hibernateSession) {
		hibernateSession.save(auth);
	}
}
