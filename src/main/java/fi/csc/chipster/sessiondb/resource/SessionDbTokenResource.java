package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.SessionToken.Access;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.auth.resource.AuthTokens;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Session;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

/**
 * Create dataset and session tokens
 * 
 * Check that user is allowed to access the requested resources and then get 
 * the token from the auth service. 
 * 
 * @author klemela
 *
 */
@Path("tokens")
public class SessionDbTokenResource {	
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private RuleTable ruleTable;

	private AuthenticationClient authService;


	public SessionDbTokenResource(RuleTable ruleTable, AuthenticationClient authService) {
		this.ruleTable = ruleTable;
		this.authService = authService;
	}
	
	@POST
	@RolesAllowed({ Role.CLIENT, Role.SCHEDULER })
    @Path("sessions/{sessionId}")
	@Produces(MediaType.TEXT_PLAIN)
    @Transaction
    public Response postSessionToken(@PathParam("sessionId") UUID sessionId, @QueryParam("valid") String validString, @Context SecurityContext sc) throws IOException {
		
		AuthPrincipal authPrincipal = (AuthPrincipal)sc.getUserPrincipal();
    	
		// client can create read-only tokens for session-worker
		String username = sc.getUserPrincipal().getName();
		Access access = Access.READ_ONLY;
		
		// scheduler can create read-write tokens for jobs
		if (authPrincipal.getRoles().contains(Role.SCHEDULER)) {
			username = Role.SINGLE_SHOT_COMP;
			access = Access.READ_WRITE;
		}
		
		boolean requireReadWrite = access == Access.READ_WRITE;
			
		// check that the user is allowed to access the session (with auth token)
		Session session = ruleTable.checkSessionAuthorization(sc, sessionId, requireReadWrite, false);
		
		Instant valid = AuthTokens.parseValid(validString);
				
		if (session.getSessionId() == null) {
			throw new IllegalArgumentException("cannot create token for null session");
		}
		
		String sessionDbToken;
		try {
			sessionDbToken = authService.createSessionToken(username, sessionId, valid, access);
			
		} catch (RestException e) {
			throw new InternalServerErrorException("failed to get restricted token from auth", e);
		}		
		
    	return Response.ok(sessionDbToken).build();
    }
	
	@POST
	@RolesAllowed(Role.CLIENT)
    @Path("sessions/{sessionId}/datasets/{datasetId}")
	@Produces(MediaType.TEXT_PLAIN)
    @Transaction
    public Response postDatasetToken(@PathParam("sessionId") UUID sessionId, @PathParam("datasetId") UUID datasetId, @QueryParam("valid") String validString, @Context SecurityContext sc) throws IOException {
    	
		// check that the user is allowed to access the session (with auth token)
		// read-only access is enough, because this doesn't change the session (and is needed for example sessions)
		Dataset dataset = ruleTable.checkDatasetReadAuthorization(sc, sessionId, datasetId);
		
		Session session = ruleTable.getSession(dataset.getSessionId());
		
		Instant valid = AuthTokens.parseValid(validString);
		
		String username = sc.getUserPrincipal().getName();
		
		if (session.getSessionId() == null) {
			throw new IllegalArgumentException("cannot create token for null session");
		}
		
		if (dataset.getDatasetId() == null) {
			throw new IllegalArgumentException("cannot create token for null dataset");
		}
		
		String datasetToken = null;
		try {
			datasetToken = authService.createDatasetToken(username, sessionId, datasetId, valid);
			
		} catch (RestException e) {
			throw new InternalServerErrorException("failed to get restricted token from auth");
		}		
		
    	return Response.ok(datasetToken).build();
    }
}
