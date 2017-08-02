package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

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

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
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
			throw new NotAuthorizedException("username is null");
		}
		
		// checks authorization
		Dataset dataset = authorizationResource.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, datasetId, true);
		
		LocalDateTime valid = null;
		if (validString == null) {
			valid = LocalDateTime.now().plus(Duration.ofSeconds(DATASET_TOKEN_VALID_DEFAULT));
		} else {
			try {
				valid = LocalDateTime.parse(validString);
			} catch (DateTimeParseException e) {
				logger.error("query parameter 'valid' can't be parsed to LocalDateTime", e);
				throw new BadRequestException("query parameter 'valid' can't be parsed to LocalDateTime");
			}
		}
		
		DatasetToken datasetToken = new DatasetToken(RestUtils.createUUID(), sc.getUserPrincipal().getName(), dataset.getSession(), dataset, valid);
		
		datasetTokenTable.save(datasetToken);
		
    	return Response.ok(datasetToken).build();    	
    }
}
