package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.ForbiddenException;
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

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.DatasetToken;

@Path("datasettokens")
public class DatasetTokenResource {	
	
	private static final long DATASET_TOKEN_VALID_DEFAULT = 60; // seconds

	private static Logger logger = LogManager.getLogger();

	private DatasetTokenTable datasetTokenTable;

	private RuleTable authorizationResource;

	public DatasetTokenResource(DatasetTokenTable datasetTokenTable, RuleTable authorizationResource) {
		this.datasetTokenTable = datasetTokenTable;
		this.authorizationResource = authorizationResource;
	}
	
	@POST
    @Path("sessions/{sessionId}/datasets/{datasetId}")
	@Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response post(@PathParam("sessionId") UUID sessionId, @PathParam("datasetId") UUID datasetId, @QueryParam("valid") String validString, @Context SecurityContext sc) throws IOException {
    	
		String username = sc.getUserPrincipal().getName();
		
		if(username == null) {
			// this is 403 because TokenRequestFilter would have already thrown 401 if the 
			// authentication header was missing
			throw new ForbiddenException("token not found");
		}
		
		// checks authorization
		// Allow with read-only permissions to make the example sessions work. 
		// Although the token is written to a DB, this doesn't really change anything for others.
		Dataset dataset = authorizationResource.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, datasetId, false);
		
		Instant valid = null;
		if (validString == null) {
			valid = Instant.now().plus(Duration.ofSeconds(DATASET_TOKEN_VALID_DEFAULT));
		} else {
			try {
				valid = Instant.parse(validString);
			} catch (DateTimeParseException e) {
				logger.error("query parameter 'valid' can't be parsed to Instant", e);
				throw new BadRequestException("query parameter 'valid' can't be parsed to Instant");
			}
		}
		
		DatasetToken datasetToken = new DatasetToken(RestUtils.createUUID(), sc.getUserPrincipal().getName(), dataset.getSession(), dataset, valid);
		
		datasetTokenTable.save(datasetToken);
		
    	return Response.ok(datasetToken).build();    	
    }
}
