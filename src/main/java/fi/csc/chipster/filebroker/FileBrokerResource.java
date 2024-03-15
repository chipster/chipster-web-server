package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.filestorage.FileStorageClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServletUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.exception.InsufficientStorageException;
import fi.csc.chipster.s3storage.ChipsterChecksums;
import fi.csc.chipster.s3storage.FileEncryption;
import fi.csc.chipster.s3storage.IllegalFileException;
import fi.csc.chipster.s3storage.S3StorageClient;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.FileState;
import fi.csc.chipster.sessiondb.resource.SessionDatasetResource;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.InternalServerErrorException;
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
	private StorageDiscovery storageDiscovery;
	private S3StorageClient s3StorageClient;
	private FileEncryption fileEncryption;

	public FileBrokerResource(ServiceLocatorClient serviceLocator, SessionDbClient sessionDbClient,
			StorageDiscovery storageDiscovery, Config config) throws NoSuchAlgorithmException {

		this.sessionDbWithFileBrokerCredentials = sessionDbClient;
		this.sessionDbUri = serviceLocator.getInternalService(Role.SESSION_DB).getUri();
		this.sessionDbEventsUri = serviceLocator.getInternalService(Role.SESSION_DB_EVENTS).getUri();
		this.storageDiscovery = storageDiscovery;

		this.s3StorageClient = new S3StorageClient(config);
		this.fileEncryption = new FileEncryption();
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

		FileStorageClient storageClient = storageDiscovery.getStorageClientForExistingFile(storageId);

		ResponseBuilder response = null;

		try {

			java.io.File tmpFile = Files.createTempFile("chipster-s3-upload-temp", ".enc").toFile();
			java.io.File decryptedFile = Files.createTempFile("chipster-s3-upload-temp", ".dec").toFile();
			s3StorageClient.download("s3-file-broker-test", dataset.getFile().getFileId().toString(), tmpFile);

			SecretKey key = this.fileEncryption.parseKey(dataset.getFile().getEncryptionKey());

			this.fileEncryption.decrypt(key, tmpFile, decryptedFile);

			String checksum = ChipsterChecksums.checksum(decryptedFile.getPath(), new CRC32());

			if (dataset.getFile().getChecksum() != null) {
				if (checksum.equals(dataset.getFile().getChecksum())) {
					logger.info("checksum ok: " + checksum);
				} else {
					throw new InternalServerErrorException("checksum error");
				}
			} else {
				logger.info("checksum not available");
			}

			response = Response.ok(decryptedFile);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvalidKeyException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NoSuchPaddingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvalidAlgorithmParameterException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalBlockSizeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (BadPaddingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalFileException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (DecoderException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// InputStream fileStream;
		// try {
		// fileStream = storageClient.download(fileId, range);
		// } catch (RestException e) {
		// throw ServletUtils.extractRestException(e);
		// }

		// ResponseBuilder response = Response.ok(fileStream);

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

		// checks authorization
		Dataset dataset;
		try {
			dataset = getDatasetObject(sessionId, datasetId, userToken, true);
		} catch (RestException e) {
			throw ServletUtils.extractRestException(e);
		}

		long fileLength = -1;
		String checksum = null;
		String key = null;

		if (dataset.getFile() == null) {

			logger.debug("PUT new file");

			File file = new File();
			// create a new fileId
			file.setFileId(RestUtils.createUUID());
			file.setFileCreated(Instant.now());
			dataset.setFile(file);

			for (String storageId : storageDiscovery.getStoragesForNewFile()) {
				// not synchronized, may fail when storage is lost
				FileStorageClient storageClient = storageDiscovery.getStorageClient(storageId);

				logger.info("PUT new file to storage '" + storageId + "' "
						+ FileBrokerAdminResource.humanFriendly(flowTotalSize));
				try {
					// storageClient.checkIfUploadAllowed(queryParams);
					// fileLength = storageClient.upload(dataset.getFile().getFileId(), fileStream,
					// queryParams);

					try {
						java.io.File tmpFile = Files.createTempFile("chipster-s3-upload-temp", "").toFile();
						java.io.File encryptedFile = Files.createTempFile("chipster-s3-upload-temp", ".enc").toFile();

						FileUtils.copyInputStreamToFile(fileStream, tmpFile);

						// fast checksum to detect bugs. Definitely not cryptographically secure
						checksum = ChipsterChecksums.checksum(tmpFile.getPath(), new CRC32());

						// new key for each file
						SecretKey secretKey = this.fileEncryption.generateKey();
						this.fileEncryption.encrypt(secretKey, tmpFile, encryptedFile);

						// let's store these in hex to make them easier to handle in command line tools
						key = Hex.encodeHexString(secretKey.getEncoded());

						s3StorageClient.upload("s3-file-broker-test", encryptedFile,
								dataset.getFile().getFileId().toString());

						fileLength = tmpFile.length();

						tmpFile.delete();
						encryptedFile.delete();

					} catch (IOException | NoSuchAlgorithmException | InvalidKeyException | NoSuchPaddingException
							| InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}

					file.setStorage(storageId);
					break;
				} catch (InsufficientStorageException e) {
					logger.warn("insufficient storage in storageId '" + storageId + "', trying others");
					// } catch (RestException e) {
					// throw ServletUtils.extractRestException(e);
				} catch (InterruptedException e) {
					logger.warn("interrupted", e.getMessage());
				}
			}

			if (fileLength == -1) {
				logger.error("insufficient storage on all storages");
				throw new InsufficientStorageException("insufficient storage on all storages");
			}
		} else {

			String storageId = dataset.getFile().getStorage();
			FileStorageClient storageClient = storageDiscovery.getStorageClientForExistingFile(storageId);

			logger.info("PUT file exists in storage '" + storageId + "'");
			fileLength = storageClient.upload(dataset.getFile().getFileId(), fileStream, queryParams);
		}

		logger.info("PUT update file size " + FileBrokerAdminResource.humanFriendly(fileLength));

		if (fileLength >= 0) {
			// update the file size after each chunk
			dataset.getFile().setSize(fileLength);
			dataset.getFile().setChecksum(checksum);
			dataset.getFile().setEncryptionKey(key);

			// update File state
			if (flowTotalSize == null) {
				logger.warn("flowTotalSize is not available, will assume the file is completed");
				dataset.getFile().setState(FileState.COMPLETE);

			} else if (fileLength == flowTotalSize) {
				dataset.getFile().setState(FileState.COMPLETE);

			} else {
				dataset.getFile().setState(FileState.UPLOADING);
			}

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