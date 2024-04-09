package fi.csc.chipster.filebroker;

import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.ResetException;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.filestorage.client.FileStorageClient;
import fi.csc.chipster.filestorage.client.FileStorageDiscovery;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServletUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.exception.ConflictException;
import fi.csc.chipster.rest.exception.InsufficientStorageException;
import fi.csc.chipster.s3storage.client.S3StorageClient;
import fi.csc.chipster.s3storage.client.S3StorageClient.ByteRange;
import fi.csc.chipster.s3storage.client.S3StorageClient.ChipsterUpload;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbAdminClient;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.FileState;
import fi.csc.chipster.sessiondb.resource.SessionDatasetResource;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.ResponseBuilder;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

@Path("/")
public class FileBrokerResource {

	public static final String FLOW_TOTAL_CHUNKS = "flowTotalChunks";
	public static final String FLOW_CHUNK_SIZE = "flowChunkSize";
	public static final String FLOW_CHUNK_NUMBER = "flowChunkNumber";
	public static final String FLOW_TOTAL_SIZE = "flowTotalSize";

	public static final String HEADER_RANGE = "Range";

	private static Logger logger = LogManager.getLogger();

	private SessionDbClient sessionDbWithFileBrokerCredentials;
	private String sessionDbUri;
	private String sessionDbEventsUri;
	private FileStorageDiscovery storageDiscovery;
	private S3StorageClient s3StorageClient;
	private SessionDbAdminClient sessionDbAdminClient;

	public FileBrokerResource(ServiceLocatorClient serviceLocator, SessionDbClient sessionDbClient,
			SessionDbAdminClient sessionDbAdminClient, FileStorageDiscovery storageDiscovery,
			S3StorageClient s3StorageClient, Config config)
			throws NoSuchAlgorithmException {

		this.sessionDbUri = serviceLocator.getInternalService(Role.SESSION_DB).getUri();
		this.sessionDbEventsUri = serviceLocator.getInternalService(Role.SESSION_DB_EVENTS).getUri();
		this.storageDiscovery = storageDiscovery;
		this.s3StorageClient = s3StorageClient;
		// check permissions carefully before using these!
		this.sessionDbWithFileBrokerCredentials = sessionDbClient;
		this.sessionDbAdminClient = sessionDbAdminClient;
	}

	@GET
	@Path("sessions/{sessionId}/datasets/{datasetId}")
	public Response getDataset(@PathParam("sessionId") UUID sessionId, @PathParam("datasetId") UUID datasetId,
			@QueryParam("download") String downloadParam, @QueryParam("type") String typeParam,
			@HeaderParam(HEADER_RANGE) String range, @Context SecurityContext sc, @Context UriInfo uriInfo) {

		if (logger.isDebugEnabled()) {
			logger.debug("GET " + uriInfo.getAbsolutePath() + " " + RestUtils.asJson(uriInfo.getQueryParameters()));
		}

		// get query parameters
		// empty string when set, otherwise null (boolean without value would have been
		// false)
		boolean download = downloadParam != null;
		boolean type = typeParam != null;

		String userToken = ((AuthPrincipal) sc.getUserPrincipal()).getTokenKey();

		Dataset dataset;

		// checks authorization
		try {
			dataset = getDatasetObject(sessionId, datasetId, userToken, false);
		} catch (RestException e) {
			throw ServletUtils.extractRestException(e);
		}

		if (dataset.getFile() == null || dataset.getFile().getFileId() == null) {
			throw new NotFoundException("file id is null");
		}

		UUID fileId = dataset.getFile().getFileId();
		String storageId = dataset.getFile().getStorage();

		logger.info("GET from storage '" + storageId + "' "
				+ FileBrokerAdminResource.humanFriendly(dataset.getFile().getSize()));

		InputStream fileStream;

		if (this.s3StorageClient.containsStorageId(storageId)) {

			ByteRange byteRange = s3StorageClient.parseByteRange(range);

			fileStream = s3StorageClient.downloadAndDecrypt(dataset.getFile(), byteRange);

		} else {

			FileStorageClient storageClient = storageDiscovery.getStorageClientForExistingFile(storageId);

			try {
				fileStream = storageClient.download(fileId, range);
			} catch (RestException e) {
				throw ServletUtils.extractRestException(e);
			}
		}

		ResponseBuilder response = Response.ok(fileStream);

		if (range == null || range.isEmpty()) {
			/*
			 * Set content-length header
			 * 
			 * This is not strictly needed, but it's difficult to report errors after the
			 * response code has been sent, but at
			 * least Firefox seems to show the download as blocked or failed if the stream
			 * doesn't have as many bytes as we say here.
			 */
			response.header("content-length", dataset.getFile().getSize());
		}

		// configure filename for response
		if (!download) {
			RestUtils.configureFilename(response, dataset.getName());
		} else {
			RestUtils.configureForDownload(response, dataset.getName());
		}

		if (type) {
			// rendenring a html file in an iFrame requires the Content-Type header
			response.type(getType(dataset).toString());
		} else {
			/*
			 * HTTP messages should contain content-type. but it's not required. The old
			 * servlet implementation didn't set it and the browsers were guessing it fine.
			 * I didn't find a way to remove the header in Jersey, but the wildcard
			 * content-type seems to cause the same end result.
			 */
			response.type(MediaType.WILDCARD_TYPE);
		}

		return response.build();
	}

	@PUT
	@Path("sessions/{sessionId}/datasets/{datasetId}")
	public Response putDataset(@PathParam("sessionId") UUID sessionId, @PathParam("datasetId") UUID datasetId,
			InputStream fileStream, @QueryParam(FLOW_CHUNK_NUMBER) Long chunkNumber,
			@QueryParam(FLOW_CHUNK_SIZE) Long chunkSize, @QueryParam(FLOW_TOTAL_CHUNKS) Long flowTotalChunks,
			@QueryParam(FLOW_TOTAL_SIZE) Long flowTotalSize, @Context SecurityContext sc, @Context UriInfo uriInfo) {

		if (logger.isDebugEnabled()) {
			logger.debug("PUT " + uriInfo.getAbsolutePath() + " " + RestUtils.asJson(uriInfo.getQueryParameters()));
		}

		String userToken = ((AuthPrincipal) sc.getUserPrincipal()).getTokenKey();

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
		if (flowTotalSize != null) {
			queryParams.put(FLOW_TOTAL_SIZE, "" + flowTotalSize);
		}

		logger.info("chunkNumber: " + chunkNumber);
		logger.info("chunkSize: " + chunkSize);
		logger.info("flowTotalChunks: " + flowTotalChunks);
		logger.info("flowTotalSize: " + flowTotalSize);

		// checks authorization
		Dataset dataset;
		try {
			dataset = getDatasetObject(sessionId, datasetId, userToken, true);
		} catch (RestException e) {
			throw ServletUtils.extractRestException(e);
		}

		File file;

		if (dataset.getFile() == null) {

			// find storage for new file

			logger.debug("PUT new file");

			file = new File();
			// create a new fileId
			file.setFileId(RestUtils.createUUID());
			file.setFileCreated(Instant.now());
			dataset.setFile(file);

			file.setStorage(getStorage(flowTotalChunks, queryParams));
			file.setState(FileState.UPLOADING);

			// Add the File to the DB before creating the file in storage. Otherwise storage
			// check could think the file in storage is orphan and delete it.
			try {
				this.sessionDbWithFileBrokerCredentials.updateDataset(sessionId, dataset);
			} catch (RestException e) {
				throw ServletUtils.extractRestException(e);
			}

		} else {

			// find the storage of existing file for appending

			file = dataset.getFile();

			if (this.s3StorageClient.containsStorageId(file.getStorage())) {

				if (file.getState() == FileState.UPLOADING) {

					/*
					 * Restart paused upload
					 * 
					 * User probably has paused an upload and now continues it.
					 * This is fine for us, because we use S3 only for one-part uploads,
					 * so the app will upload the whole file again.
					 * 
					 * This doesn't save anything in the network transfers, but now the UI
					 * doesn't need to know that S3 doesn't support pause and continue.
					 */

					logger.info("upload restarted");

				} else {

					// don't allow changes to complete files
					throw new ConflictException("file exists already");
				}
			} else {
				logger.info("append to existing file in " + file.getStorage());
			}
		}

		if (this.s3StorageClient.containsStorageId(file.getStorage())) {

			logger.info("upload to S3 bucket " + this.s3StorageClient.storageIdToBucket(file.getStorage()));

			// checksum is not available
			try {
				ChipsterUpload upload = this.s3StorageClient.encryptAndUpload(file.getFileId(),
						fileStream, flowTotalSize, file.getStorage(), null);

				file.setSize(upload.getFileLength());
				file.setChecksum(upload.getChecksum());
				file.setEncryptionKey(upload.getEncryptionKey());
				file.setState(FileState.COMPLETE);

			} catch (ResetException e) {
				logger.warn("upload cancelled", e.getClass());

				try {
					dataset.setFile(null);
					this.sessionDbWithFileBrokerCredentials.updateDataset(sessionId, dataset);
					this.sessionDbAdminClient.deleteFile(file.getFileId());
				} catch (RestException e1) {
					logger.error("failed to delete File from DB", e1);
				}

				throw new BadRequestException("upload cancelled");
			}

		} else {

			logger.info("PUT file to storage '" + file.getStorage() + "' "
					+ FileBrokerAdminResource.humanFriendly(flowTotalSize));

			// not synchronized, may fail when storage is lost
			FileStorageClient storageClient = storageDiscovery.getStorageClient(file.getStorage());

			// update the file size after each chunk
			file.setSize(storageClient.upload(dataset.getFile().getFileId(), fileStream,
					queryParams));

			// update File state
			if (flowTotalSize == null) {
				logger.warn("flowTotalSize is not available, will assume the file is completed");
				file.setState(FileState.COMPLETE);

			} else if (file.getSize() == flowTotalSize) {
				file.setState(FileState.COMPLETE);

			} else {
				file.setState(FileState.UPLOADING);
			}
		}

		logger.info("PUT update file size " + FileBrokerAdminResource.humanFriendly(file.getSize()));

		if (file.getSize() >= 0) {

			try {
				logger.debug("PUT " + sessionDbUri + "/sessions/" + sessionId + "/datasets/" + datasetId);
				this.sessionDbWithFileBrokerCredentials.updateDataset(sessionId, dataset);
				return Response.noContent().build();

			} catch (RestException e) {
				throw ServletUtils.extractRestException(e);
			}
		} else {
			// upload paused
			return Response.serverError().build();
		}
	}

	private String getStorage(Long flowTotalChunks, Map<String, String> queryParams) {

		if (this.s3StorageClient.isEnabledForNewFiles() && this.s3StorageClient.isOnePartUpload(flowTotalChunks)) {

			return this.s3StorageClient.getStorageIdForNewFile();

		} else {

			for (String storageId : storageDiscovery.getStoragesForNewFile()) {
				// not synchronized, may fail when storage is lost
				FileStorageClient storageClient = storageDiscovery.getStorageClient(storageId);

				try {
					storageClient.checkIfUploadAllowed(queryParams);

					return storageId;

				} catch (InsufficientStorageException e) {
					logger.warn("insufficient storage in storageId '" + storageId + "', trying others");
				} catch (RestException e) {
					throw ServletUtils.extractRestException(e);
				}
			}

			logger.error("insufficient storage on all storages");
			throw new InsufficientStorageException("insufficient storage on all storages");
		}
	}

	private Dataset getDatasetObject(UUID sessionId, UUID datasetId, String userToken, boolean requireReadWrite)
			throws RestException {

		// check authorization
		SessionDbClient sessionDbWithUserCredentials = new SessionDbClient(sessionDbUri, sessionDbEventsUri,
				new StaticCredentials("token", userToken));

		logger.debug("curl --user token:" + userToken + " " + sessionDbUri + "/sessions/" + sessionId + "/datasets/"
				+ datasetId + "?" + SessionDatasetResource.QUERY_PARAM_READ_WRITE + "=" + requireReadWrite);
		Dataset dataset = sessionDbWithUserCredentials.getDataset(sessionId, datasetId, requireReadWrite);

		if (dataset == null) {
			throw new ForbiddenException("dataset not found");
		}

		return dataset;
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