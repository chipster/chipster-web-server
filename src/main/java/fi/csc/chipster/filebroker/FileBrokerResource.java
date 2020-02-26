package fi.csc.chipster.filebroker;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.filestorage.FileStorageClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;

@Path("/")
public class FileBrokerResource {
	
	public static final String HEADER_RANGE = "Range";

	private static final String FLOW_TOTAL_CHUNKS = "flowTotalChunks";

	private static final String FLOW_CHUNK_SIZE = "flowChunkSize";

	private static final String FLOW_CHUNK_NUMBER = "flowChunkNumber";

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	@SuppressWarnings("unused")
	private Config config;
	@SuppressWarnings("unused")
	private ServiceLocatorClient serviceLocator;

	private SessionDbClient sessionDbWithFileBrokerCredentials;

	private FileStorageClient fileStorageClient;

	private String sessionDbUri;

	private String sessionDbEventsUri;
	
	public FileBrokerResource(ServiceLocatorClient serviceLocator, SessionDbClient sessionDbClient, AuthenticationClient authService, Config config) {
		this.serviceLocator = serviceLocator;
		this.sessionDbWithFileBrokerCredentials = sessionDbClient;
		this.config = config;
		
		this.sessionDbUri = serviceLocator.getInternalService(Role.SESSION_DB).getUri();
		this.sessionDbEventsUri = serviceLocator.getInternalService(Role.SESSION_DB_EVENTS).getUri();
		this.fileStorageClient = new FileStorageClient(serviceLocator, authService.getCredentials());
	}

	@GET
	@Path("sessions/{sessionId}/datasets/{datasetId}")
	public Response getDataset(
			@PathParam("sessionId") UUID sessionId, 
			@PathParam("datasetId") UUID datasetId,
			@QueryParam("download") String downloadParam,
			@QueryParam("type") String typeParam,
			@HeaderParam(HEADER_RANGE) String range,
			@Context SecurityContext sc) {
		
		// get query parameters
		// empty string when set, otherwise null (boolean without value would have been false)
		boolean download = downloadParam != null;
		boolean type = typeParam != null;
		
		String userToken = ((AuthPrincipal)sc.getUserPrincipal()).getTokenKey();

		Dataset dataset; 
		
		// checks authorization
		try {
			dataset = getDatasetObject(sessionId, datasetId, userToken, false);
		} catch (RestException e) {
			throw extractRestException(e);
		}

		if (dataset.getFile() == null || dataset.getFile().getFileId() == null) {
			throw new NotFoundException("file id is null");
		}

		UUID fileId = dataset.getFile().getFileId();

		InputStream fileStream;
		try {
			fileStream = fileStorageClient.download(fileId, range);
		} catch (RestException e) {
			throw extractRestException(e);
		}
		
		ResponseBuilder response = Response.ok(fileStream);
		
		if (download) {
			// hint filename for dataset export
			RestUtils.configureForDownload(response, dataset.getName());
		}

		if (type) {
			// rendenring a html file in an iFrame requires the Content-Type header
			response.type(getType(dataset).toString());
		} else {
			/* HTTP messages should contain content-type. but it's not required. The old servlet implementation
			 * didn't set it and the browsers were guessing it fine. I didn't find a way to remove the header in
			 * Jersey, but the wildcard content-type seems to cause the same end result.  
			 */
			response.type(MediaType.WILDCARD_TYPE);
		}
		
		return response.build();
	}
	
	@PUT
	@Path("sessions/{sessionId}/datasets/{datasetId}")
	public Response putDataset(
			@PathParam("sessionId") UUID sessionId, 
			@PathParam("datasetId") UUID datasetId,
			InputStream fileStream,
			@QueryParam(FLOW_CHUNK_NUMBER) Long chunkNumber,
			@QueryParam(FLOW_CHUNK_SIZE) Long chunkSize,
			@QueryParam(FLOW_TOTAL_CHUNKS) Long flowTotalChunks,
			@Context SecurityContext sc) {
		
		String userToken = ((AuthPrincipal)sc.getUserPrincipal()).getTokenKey();

		Map<String, String> queryParams = new HashMap<>();
		
		if (chunkNumber != null) {
			queryParams.put(FLOW_CHUNK_NUMBER, "" + chunkNumber);
		}
		if (chunkSize != null) {
			queryParams.put(FLOW_CHUNK_SIZE, "" + chunkSize);
		}
		if (flowTotalChunks != null) {
			queryParams.put(FLOW_TOTAL_CHUNKS, "" + flowTotalChunks);
		}
		
		// checks authorization
		Dataset dataset;
		try {
			dataset = getDatasetObject(sessionId, datasetId, userToken, true);
		} catch (RestException e) {
			throw extractRestException(e);
		}
		
		if (dataset.getFile() == null) {
			File file = new File();
			// create a new fileId
			file.setFileId(RestUtils.createUUID());
			file.setFileCreated(Instant.now());
			dataset.setFile(file);
		}
		
		long fileLength = this.fileStorageClient.upload(dataset.getFile().getFileId(), fileStream, queryParams);

		if (fileLength >= 0) {
			// update the file size after each chunk
			dataset.getFile().setSize(fileLength);
		
			try {
				this.sessionDbWithFileBrokerCredentials.updateDataset(sessionId, dataset);
				return Response.noContent().build();
				
			} catch (RestException e) {
				throw extractRestException(e);
			}
		} else {
			// upload paused
			return Response.serverError().build();
		}
	}
	
	
	private Dataset getDatasetObject(UUID sessionId, UUID datasetId, String userToken, boolean requireReadWrite) throws RestException {

		// check authorization
		SessionDbClient sessionDbWithUserCredentials = new SessionDbClient(sessionDbUri, sessionDbEventsUri,
				new StaticCredentials("token", userToken));

		Dataset dataset = sessionDbWithUserCredentials.getDataset(sessionId, datasetId, requireReadWrite);

		if (dataset == null) {
			throw new ForbiddenException("dataset not found");
		}

		return dataset;
	}
	
	

	private WebApplicationException extractRestException(RestException e) {
		int statusCode = e.getResponse().getStatus();
		String msg = e.getMessage();
		if (statusCode == HttpServletResponse.SC_FORBIDDEN) {
			return new ForbiddenException(msg);
		} else if (statusCode == HttpServletResponse.SC_UNAUTHORIZED) {
			return new NotAuthorizedException(msg);
		} else if (statusCode == HttpServletResponse.SC_NOT_FOUND) {
			return new NotFoundException(msg);
		} else {
			return new InternalServerErrorException(e);
		}
	}

	private MediaType getType(Dataset dataset) {
		MediaType type = null;
		// we should store the recognized type so that client could rely on it
		if (dataset.getName().toLowerCase().endsWith(".html")) {
			// required for visualizing html files in an iFrame
			type = MediaType.TEXT_HTML_TYPE;
		}
		return type;
	}
}