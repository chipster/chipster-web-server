package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;

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
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.sessiondb.model.Authorization;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Session;

@Path("authorizations")
public class AuthorizationResource {	
	
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;

	private Config config;

	private Set<String> servicesAccounts;

	private DatasetTokenTable datasetTokenTable;

	private TokenRequestFilter tokenRequestFilter;

	private SessionResource authorizationRemovedListener;

	public AuthorizationResource(HibernateUtil hibernate, DatasetTokenTable datasetTokenTable, TokenRequestFilter tokenRequestFilter) {
		this.hibernate = hibernate;
		this.config = new Config();
		this.servicesAccounts = config.getServicePasswords().keySet();
		this.datasetTokenTable = datasetTokenTable;
		this.tokenRequestFilter = tokenRequestFilter;
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
    public Response getBySession(@QueryParam("sessionId") UUID sessionId, @Context SecurityContext sc) {
    	
    	if (sessionId != null) {
    	
	    	checkAuthorization(sc.getUserPrincipal().getName(), sessionId, false);    
	    	List<Authorization> authorizations = getAuthorizations(sessionId);    	
	    	return Response.ok(authorizations).build();
	    	
    	} else if (Role.SESSION_DB.equals(sc.getUserPrincipal().getName())) {
    		
        	// session db is allowed to get all
        	return getAuthorizations();
        	
    	} else {
    		throw new ForbiddenException("sessionId query param is null");
    	}
    }
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transaction
    public Response post(Authorization newAuthorization, @Context UriInfo uriInfo, @Context SecurityContext sc) {
    	
		if (newAuthorization.getAuthorizationId() != null) {
			throw new BadRequestException("authorization already has an id, post not allowed");
		}
    	
    	UUID sessionId = newAuthorization.getSession().getSessionId();
    	
    	Authorization userAuthorization = checkAuthorization(sc.getUserPrincipal().getName(), sessionId, true);
    	newAuthorization.setAuthorizationId(RestUtils.createUUID());
    	
    	// don't trust the session that came from the client
    	newAuthorization.setSession(userAuthorization.getSession());
    	
    	save(newAuthorization, hibernate.session());
    
    	URI uri = uriInfo.getAbsolutePathBuilder().path(newAuthorization.getAuthorizationId().toString()).build();
    	
    	return Response.created(uri).build();
    }
    
    @DELETE
    @Path("{id}")
    @Transaction
    public Response delete(@PathParam("id") UUID authorizationId, @Context SecurityContext sc) {
    	
    	Authorization authorizationToDelete = getAuthorization(authorizationId, hibernate.session());

    	Session session = authorizationToDelete.getSession();
    	
    	checkAuthorization(sc.getUserPrincipal().getName(), session.getSessionId(), true);
    	    	
    	delete(authorizationToDelete);    	
    
    	return Response.noContent().build();
    }
    
    public void delete(Authorization authorization) {
    	hibernate.session().delete(authorization);
    	
    	if (authorizationRemovedListener != null) {
    		authorizationRemovedListener.authorizationRemoved(authorization);
    	}
    }
    
    public Authorization checkAuthorization(String username, UUID sessionId, boolean requireReadWrite) {
    	return checkAuthorization(username, sessionId, requireReadWrite, hibernate.session());
    }
    
	/**
	 * Check if username is authorized to access or modify the whole session
	 * 
	 * @param username
	 * @param sessionId
	 * @param requireReadWrite
	 * @param hibernateSession
	 * @return
	 */
	public Authorization checkAuthorization(String username, UUID sessionId, boolean requireReadWrite, org.hibernate.Session hibernateSession) {

		if(username == null) {
			throw new ForbiddenException("username is null");
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
	
	@SuppressWarnings("unchecked")
	public List<Authorization> getAuthorizations(UUID sessionId) {		
		return hibernate.session()
				.createQuery("from Authorization where session_sessionId=:sessionId")
				.setParameter("sessionId", sessionId)
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
		
		Authorization auth = (Authorization) hibernateSession
				.createQuery("from Authorization where username=:username and session=:session")
				.setParameter("username", username)
				.setParameter("session", session)
				.uniqueResult();
		
		logger.debug("check authorization " + username + " " + session.getSessionId().toString().substring(0, 4) + ", found: " + (auth != null));
		
		return auth;
	}

	/**
	 * Persist the authorization and its session in the DB
	 * 
	 * @param auth
	 * @param hibernateSession 
	 */
	public void save(Authorization auth, org.hibernate.Session hibernateSession) {
		logger.debug("save authorization " + auth.getUsername() + " " + auth.getSession().getSessionId().toString().substring(0, 4));
		hibernateSession.save(auth);
	}

	/**
	 * Check the token is allowed to access a specific dataset
	 * 
	 * Access is allowed if either
	 * 1. auth-service accepts the token and the corresponding username has an Authorization to access the session
	 * 2. the token is a DatasetToken for the requested Dataset
	 * 
	 * @param userToken
	 * @param sessionId
	 * @param datasetId
	 * @param requireReadWrite
	 * @return
	 */
	public void checkAuthorizationWithToken(String userToken, UUID sessionId, UUID datasetId, boolean requireReadWrite) {
		try {
			// check that the token is valid and get the username
			String username = tokenRequestFilter.tokenAuthentication(userToken).getName();

			checkAuthorization(username, sessionId, datasetId, requireReadWrite);
		} catch (ForbiddenException e) {
			if (requireReadWrite) {
				// write access is allowed only with the first method
				throw e;
			} else {
				// read access to a specific dataset is possible with a DatasetToken 
				datasetTokenTable.checkAuthorization(UUID.fromString(userToken), sessionId, datasetId);
			}
		}
	}
	
	/**
	 * Check that the username is authorized to access a specific dataset
	 * 
	 * There must be an Authorization for the user to the session and the dataset must be in that session.
	 * 
	 * @param username
	 * @param sessionId
	 * @param datasetId
	 * @param requireReadWrite
	 * @return
	 */
	public Dataset checkAuthorization(String username, UUID sessionId, UUID datasetId, boolean requireReadWrite) {
		// check that the user has an Authorization to access the session
		Authorization auth = checkAuthorization(username, sessionId, requireReadWrite);

		Dataset dataset = auth.getSession().getDatasets().get(datasetId);
		// check that the requested dataset is in the session
		// otherwise anyone with a session can access any dataset
		if (dataset == null) {
			throw new NotFoundException("dataset not found");
		}
		
		return dataset;
	}

	public Session getSessionForReading(SecurityContext sc, UUID sessionId) {
		Authorization auth = checkAuthorization(sc.getUserPrincipal().getName(), sessionId, false);
		return auth.getSession();
	}
	
	public Session getSessionForWriting(SecurityContext sc, UUID sessionId) {
		Authorization auth = checkAuthorization(sc.getUserPrincipal().getName(), sessionId, true);
		return auth.getSession();
	}
	
	public Authorization getReadAuthorization(SecurityContext sc, UUID sessionId) {
    	return checkAuthorization(sc.getUserPrincipal().getName(), sessionId, false);
    }
	public Authorization getWriteAuthorization(SecurityContext sc, UUID sessionId) {
    	return checkAuthorization(sc.getUserPrincipal().getName(), sessionId, true);
    }

	public void setAuthorizationRemovedListener(SessionResource sessionResource) {
		this.authorizationRemovedListener = sessionResource;
	}
}
