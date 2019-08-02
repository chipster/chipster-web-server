package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionDbToken;

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
	@RolesAllowed(Role.CLIENT)
    @Path("sessions/{sessionId}")
	@Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response post(@PathParam("sessionId") UUID sessionId, @QueryParam("valid") String validString, @Context SecurityContext sc) throws IOException {
    	
		String username = sc.getUserPrincipal().getName();
			
		// check that the user is allowed to access the session (with auth token)
		Session session = authorizationResource.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, false);
		
		Instant valid = parseValid(validString, TOKEN_FOR_SESSION_VALID_DEFAULT);		
		// null datasetId means access to whole session
		SessionDbToken datasetToken = sessionDbTokens.createTokenForSession(username, session, valid);		
		
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
		SessionDbToken datasetToken = sessionDbTokens.createTokenForDataset(sc.getUserPrincipal().getName(), session, dataset, valid);		
		
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
