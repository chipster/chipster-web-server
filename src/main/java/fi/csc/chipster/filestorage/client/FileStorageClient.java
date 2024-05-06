package fi.csc.chipster.filestorage.client;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filebroker.FileBrokerResourceServlet;
import fi.csc.chipster.filestorage.FileServlet;
import fi.csc.chipster.filestorage.UploadCancelledException;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.ConflictException;
import fi.csc.chipster.rest.exception.InsufficientStorageException;
import fi.csc.chipster.s3storage.FileLengthException;
import fi.csc.chipster.s3storage.checksum.CheckedStream;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.model.File;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Invocation.Builder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

public class FileStorageClient {

	private static final Logger logger = LogManager.getLogger();

	private CredentialsProvider credentials;

	private WebTarget fileStorageTarget;

	/**
	 * @param serviceLocator
	 * @param credentials
	 * @param role           set to Role.CLIENT to use the public file-broker
	 *                       address, anything else e.g. Role.SERVER to the internal
	 *                       address
	 */
	public FileStorageClient(ServiceLocatorClient serviceLocator, CredentialsProvider credentials) {
		this.credentials = credentials;

		// get with credentials from ServiceLocator
		init(serviceLocator.getInternalService(Role.FILE_STORAGE).getUri());
	}

	public FileStorageClient(String url, CredentialsProvider credentials) {
		this.credentials = credentials;
		init(url);
	}

	private void init(String fileStorageUri) {

		fileStorageTarget = AuthenticationClient.getClient(credentials.getUsername(), credentials.getPassword(), true)
				.target(fileStorageUri);
	}

	// targets

	private WebTarget getFileTarget(UUID fileId) {
		return fileStorageTarget.path(FileServlet.PATH_FILES).path(fileId.toString());
	}

	// methods

	public void checkIfUploadAllowed(Long chunkNumber, Long chunkSize, Long flowTotalChunks, Long flowTotalSize)
			throws RestException {

		WebTarget target = fileStorageTarget
				.path(FileServlet.PATH_FILES)
				.path(FileServlet.PATH_PUT_ALLOWED);

		Map<String, String> queryParams = getQueryParams(chunkNumber, chunkSize, flowTotalChunks, flowTotalSize);

		for (String key : queryParams.keySet()) {
			target = target.queryParam(key, queryParams.get(key));
		}

		Response response = target.request().get();

		if (RestUtils.isSuccessful(response.getStatus())) {
			return;
		} else if (response.getStatus() == InsufficientStorageException.STATUS_CODE) {
			throw new InsufficientStorageException(response.readEntity(String.class));
		} else {
			throw new RestException("error in put allowed check", response, target.getUri());
		}
	}

	public Map<String, String> getQueryParams(Long chunkNumber, Long chunkSize, Long flowTotalChunks,
			Long flowTotalSize) {

		Map<String, String> queryParams = new HashMap<>();

		if (chunkNumber != null) {
			queryParams.put(FileBrokerResourceServlet.QP_FLOW_CHUNK_NUMBER, "" + chunkNumber);
		}
		if (chunkSize != null) {
			queryParams.put(FileBrokerResourceServlet.QP_FLOW_CHUNK_SIZE, "" + chunkSize);
		}
		if (flowTotalChunks != null) {
			queryParams.put(FileBrokerResourceServlet.QP_FLOW_TOTAL_CHUNKS, "" + flowTotalChunks);
		}
		if (flowTotalSize != null) {
			queryParams.put(FileBrokerResourceServlet.QP_FLOW_TOTAL_SIZE, "" + flowTotalSize);
		}

		return queryParams;
	}

	public long upload(UUID fileId, InputStream inputStream, Long chunkNumber, Long chunkSize, Long flowTotalChunks,
			Long flowTotalSize) {

		WebTarget target = getFileTarget(fileId);

		/*
		 * Pass the upload stream from the client to the file-storage.
		 * 
		 * The happy case would be a lot easier with Jersey http client (implementation
		 * commented out
		 * below for refrerence). However, when the user pauses the upload, we receive
		 * an EOFException from the client
		 * connection and would like to cause the same for the file-storage. With Jersey
		 * client, the file-storage will
		 * notice this only after 30 second idle timeout.
		 * 
		 * With HttpURLConnection, we can disconnect the connection causing the same
		 * exception in the file-storage.
		 */

		HttpURLConnection connection = null;

		try {

			UriBuilder uriBuilder = UriBuilder.fromUri(target.getUri());

			Map<String, String> queryParams = getQueryParams(chunkNumber, chunkSize, flowTotalChunks, flowTotalSize);

			for (String key : queryParams.keySet()) {
				uriBuilder = uriBuilder.queryParam(key, queryParams.get(key));
			}

			URL url = uriBuilder.build().toURL();
			String authoriationHeader = "Basic " + Base64.getEncoder()
					.encodeToString((credentials.getUsername() + ":" + credentials.getPassword()).getBytes());
			String contentTypeHeader = MediaType.APPLICATION_OCTET_STREAM;

			// create a new connection for every request, because HttpURLConnection isn't
			// thread safe
			connection = (HttpURLConnection) url.openConnection();
			connection.setDoOutput(true);
			connection.setRequestMethod("PUT");
			connection.setRequestProperty("Content-Type", contentTypeHeader);

			/*
			 * The default chunk size is 4k which limited the upload to about 30 MB/s.
			 * Anything between 16k and 1M seems to work fine 100-200 MB/s, when everything
			 * is communicating
			 * through localhost.
			 */
			connection.setChunkedStreamingMode(128 * 1024);
			connection.setRequestProperty("Authorization", authoriationHeader);

			logger.debug("curl -X PUT -H Authorization: " + authoriationHeader + " -H Content-Type: "
					+ contentTypeHeader + " " + url);

			IOUtils.copy(inputStream, connection.getOutputStream());

			if (RestUtils.isSuccessful(connection.getResponseCode())) {

				long fileContentLength = Long
						.parseLong(connection.getHeaderField(FileServlet.HEADER_FILE_CONTENT_LENGTH));

				logger.debug("PUT " + connection.getResponseCode() + " " + connection.getResponseMessage()
						+ " file size: " + fileContentLength);

				// if last chunk
				if (chunkNumber == null || chunkNumber.equals(flowTotalChunks)) {
					// check the file size
					if (flowTotalSize != fileContentLength) {
						throw new FileLengthException("file length error. fileId " + fileId
								+ ", uploaded: " + fileContentLength + " bytes, but expected size is " + flowTotalSize);
					}
				}

				return fileContentLength;

			} else if (connection.getResponseCode() == HttpURLConnection.HTTP_CONFLICT) {
				/*
				 * Our other Java client libraries throw RestExceptions for historical reasons.
				 * Let's throw more specific exceptions that are directly converted to error
				 * responses
				 * in the ExceptionMapper.
				 */
				throw new ConflictException(connection.getResponseMessage());

			} else if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
				throw new NotFoundException(connection.getResponseMessage());

			} else if (connection.getResponseCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
				throw new NotAuthorizedException(connection.getResponseMessage());

			} else if (connection.getResponseCode() == HttpURLConnection.HTTP_FORBIDDEN) {
				throw new ForbiddenException(connection.getResponseMessage());

			} else {
				logger.error("upload failed: unknwon response code " + connection.getResponseCode() + " "
						+ connection.getResponseMessage());
				throw new InternalServerErrorException("upload failed");
			}

		} catch (EOFException e) {
			logger.info("upload paused in file-broker: " + e.getClass().getSimpleName() + " " + e.getMessage());
			throw new UploadCancelledException("upload paused");
			// disconnect will do the same for the file-storage connection

		} catch (IOException e) {
			try {
				if (connection.getResponseCode() == InsufficientStorageException.STATUS_CODE) {
					throw new InsufficientStorageException(connection.getResponseMessage());
				} else {
					throw e;
				}
			} catch (IOException e1) {
				logger.error(e1);
			}
			throw new InternalServerErrorException("upload failed", e);
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}

		// Use chunked encoding to disable buffering. HttpUrlConnector in
		// Jersey buffers the whole file before sending it by default, which
		// won't work with big files.
		// target.property(ClientProperties .REQUEST_ENTITY_PROCESSING, "CHUNKED");
		////
		// for (String key : queryParams.keySet()) {
		// target = target.queryParam(key, queryParams.get(key));
		// }
		//
		// Response response = target.request().put(Entity.entity(inputStream,
		// MediaType.APPLICATION_OCTET_STREAM), Response.class);

		// long fileLength;
		// if (RestUtils.isSuccessful(response.getStatus())) {
		// // now FileServlet responds with the file length header, but we could also
		// make another HEAD request when it's needed
		// String lengthString =
		// response.getHeaderString(FileServlet.HEADER_FILE_CONTENT_LENGTH);
		// fileLength = Long.parseLong(lengthString);
		//
		// // update the file size after each chunk
		// return fileLength
		// } else {
		// throw new RestException("upload failed ", response, null);
		// }
	}

	public InputStream download(File file, String range) throws RestException, IOException {
		WebTarget target = getFileTarget(file.getFileId());
		Builder request = target.request();

		if (range != null) {
			request.header(FileBrokerResourceServlet.HEADER_RANGE, range);
		}

		Response response = request.get(Response.class);

		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("getting input stream failed", response, target.getUri());
		}

		InputStream fileStream = response.readEntity(InputStream.class);

		if (range == null) {
			return new CheckedStream(fileStream, null, null, file.getSize());
		}

		// we could check the range size, but we don't use range queries for anything
		// critical
		return fileStream;
	}

	public void delete(UUID fileId) throws RestException {
		WebTarget target = getFileTarget(fileId);
		Builder request = target.request();

		Response response = request.delete(Response.class);

		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("delete file error", response, target.getUri());
		}
	}
}
