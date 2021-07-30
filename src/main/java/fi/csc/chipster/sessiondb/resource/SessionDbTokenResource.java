package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionDbToken;
import fi.csc.chipster.sessiondb.model.SessionDbToken.Access;

@Path("tokens")
public class SessionDbTokenResource {	
	
	private static final long TOKEN_FOR_DATASET_VALID_DEFAULT = 60; // seconds
	
	// session downloads may take several hours
	private static final long TOKEN_FOR_SESSION_VALID_DEFAULT = 60 * 60 * 24; // seconds

	private static Logger logger = LogManager.getLogger();

	private SessionDbTokens sessionDbTokens;

	private RuleTable authorizationResource;


	public SessionDbTokenResource(SessionDbTokens datasetTokenTable, RuleTable authorizationResource) {
		this.sessionDbTokens = datasetTokenTable;
		this.authorizationResource = authorizationResource;
	}
	
	@POST
	@RolesAllowed({ Role.CLIENT, Role.SCHEDULER })
    @Path("sessions/{sessionId}")
	@Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response post(@PathParam("sessionId") UUID sessionId, @QueryParam("valid") String validString, @Context SecurityContext sc) throws IOException {
    	
		// client can create read-only tokens for session-worker
		String username = sc.getUserPrincipal().getName();
		Access access = Access.READ_ONLY;
		
		// scheduler can create read-write tokens for jobs
		if (((AuthPrincipal)sc.getUserPrincipal()).getRoles().contains(Role.SCHEDULER)) {
			username = Role.SINGLE_SHOT_COMP;
			access = Access.READ_WRITE;
		}
			
		// check that the user is allowed to access the session (with auth token)
		Session session = authorizationResource.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, false);
		
		Instant valid = parseValid(validString, TOKEN_FOR_SESSION_VALID_DEFAULT);		
		// null datasetId means access to whole session
		SessionDbToken datasetToken = sessionDbTokens.createTokenForSession(username, session, valid, access);		
		
    	return Response.ok(datasetToken).build();
    }
	
	@POST
	@RolesAllowed(Role.CLIENT)
    @Path("sessions/{sessionId}/datasets/{datasetId}")
	@Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response post(@PathParam("sessionId") UUID sessionId, @PathParam("datasetId") UUID datasetId, @QueryParam("valid") String validString, @Context SecurityContext sc) throws IOException {
    	
		// check that the user is allowed to access the session (with auth token)
		// read-only access is enough, because this doesn't change the session (and is needed for example sessions)
		Dataset dataset = authorizationResource.checkAuthorizationForDatasetRead(sc, sessionId, datasetId);
		
		Session session = authorizationResource.getSession(dataset.getSessionId());
		
		Instant valid = parseValid(validString, TOKEN_FOR_DATASET_VALID_DEFAULT);
		SessionDbToken datasetToken = sessionDbTokens.createTokenForDataset(sc.getUserPrincipal().getName(), session, dataset, valid, Access.READ_ONLY);		
		
    	return Response.ok(datasetToken).build();
    }

	private Instant parseValid(String validString, long defaultSeconds) {

		if (validString == null) {
			return Instant.now().plus(Duration.ofSeconds(defaultSeconds));
		} else {
			try {
				return Instant.parse(validString);
			} catch (DateTimeParseException e) {
				logger.error("query parameter 'valid' can't be parsed to Instant", e);
				throw new BadRequestException("query parameter 'valid' can't be parsed to Instant");
			}
		}
	}
}
