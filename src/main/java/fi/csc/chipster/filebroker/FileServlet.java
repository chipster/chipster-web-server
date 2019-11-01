package fi.csc.chipster.filebroker;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.MediaType;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.InclusiveByteRange;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.servlet.DefaultServlet;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.exception.ConflictException;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.microarray.util.IOUtils;

/**
 * <p>
 * Servlet for serving and uploading files. Extends DefaultServlet and adds
 * support for HTTP PUT method. Also adds Chipster authentication and security
 * checks.
 * </p>
 * 
 * <p>
 * It would be easier to parse the path and query parameters with JAX-RS
 * (Jersey), but it doesn't support HTTP Range queries, not even when running in
 * Jetty. Implementing range queries efficiently isn't trivial, but luckily
 * DefaultServlet supports range queries by default.
 * </p>
 */
@SuppressWarnings("serial")
public class FileServlet extends DefaultServlet implements SessionEventListener {

	private static final Logger logger = LogManager.getLogger();

	public static final int partitionLength = 2;

	// specify whether get and put requests are logged
	// using jetty debug logging is not very useful as it is so verbose
	private boolean logRest = false;

	private File storageRoot;

	private SessionDbClient sessionDbClient;

	private String sessionDbUri;

	private String sessionDbEventsUri;

	public FileServlet(File storageRoot, SessionDbClient sessionDbClient, ServiceLocatorClient serviceLocator,
			AuthenticationClient authService) {

		this.storageRoot = storageRoot;
		this.sessionDbClient = sessionDbClient;
		this.sessionDbUri = serviceLocator.getInternalService(Role.SESSION_DB).getUri();
		this.sessionDbEventsUri = serviceLocator.getInternalService(Role.SESSION_DB_EVENTS).getUri();

		logRest = true;
		logger.info("logging rest requests: " + logRest);
	}

	public static class RewrittenRequest extends HttpServletRequestWrapper {

		private String newPath;

		public RewrittenRequest(HttpServletRequest request, String newPath) {
			super(request);

			this.newPath = newPath;
		}

		@Override
		public String getPathInfo() {
			return newPath;
		}
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		if (logger.isDebugEnabled()) {
			logger.debug("RESTful file access: GET request for " + request.getRequestURI());
		}

		// get query parameters
		boolean download = request.getParameter("download") != null;
		boolean type = request.getParameter("type") != null;

		// user's token set by TokenServletFilter
		String userToken = getToken(request);

		Path path = parsePath(request.getPathInfo());

		// check authorization
		Dataset dataset;
		try {
			dataset = getDataset(path.getSessionId(), path.getDatasetId(), userToken, false);
		} catch (RestException e) {
			// authentication errors throw javax.ws.rs exceptions, but this is some other
			// error
			// and is converted to InternalServerException
			throw new ServletException(e.getMessage());
		}

		if (dataset.getFile() == null || dataset.getFile().getFileId() == null) {
			throw new NotFoundException("file id is null");
		}

		UUID fileId = dataset.getFile().getFileId();

		// get the file

		java.nio.file.Path f = getStoragePath(storageRoot.toPath(), fileId);

		if (!Files.exists(f)) {
			throw new NotFoundException("no such file");
		}

		// remove "storage/" from the beginning
		java.nio.file.Path pathUnderStorage = storageRoot.toPath().relativize(f);

		RewrittenRequest rewrittenRequest = new RewrittenRequest(request, "/" + pathUnderStorage.toString());

		if (download) {
			// hint filename for dataset export
			RestUtils.configureForDownload(response, dataset.getName());
		}

		if (type) {
			// rendenring a html file in an iFrame requires the Content-Type header
			response.setContentType(getType(dataset).toString());
		}

		Instant before = Instant.now();

		// delegate to super class
		super.doGet(rewrittenRequest, response);

		// log performance
		if (logRest) {
			logAsyncGet(request, response, f.toFile(), before);
		}
	}

	private void logAsyncGet(HttpServletRequest request, HttpServletResponse response, File f, Instant before) {

		// addListener() complains about an illegal state, if we do this before
		// super.doGET().
		// Now afterwards we have to check that the request isn't handled already
		if (request.isAsyncStarted()) {
			request.getAsyncContext().addListener(new AsyncListener() {

				@Override
				public void onTimeout(AsyncEvent event) throws IOException {
					logger.warn("async request timeout");
				}

				@Override
				public void onStartAsync(AsyncEvent event) throws IOException {
				}

				@Override
				public void onError(AsyncEvent event) throws IOException {
					logger.warn("async request error: " + ExceptionUtils.getStackTrace(event.getThrowable()));
				}

				@Override
				public void onComplete(AsyncEvent event) throws IOException {
					logGet(request, f, before);
				}
			});
		} else {
			logger.info("sync request");
			logGet(request, f, before);
		}
	}

	private void logGet(HttpServletRequest request, File f, Instant before) {

		long length = f.length();

		// parse the range header for the log message
		List<InclusiveByteRange> ranges = InclusiveByteRange
				.satisfiableRanges(request.getHeaders(HttpHeader.RANGE.asString()), f.length());
		if (ranges != null && ranges.size() == 1) {
			length = ranges.get(0).getLast() - ranges.get(0).getFirst();
		}

		Duration duration = Duration.between(before, Instant.now());
		double rate = getTransferRate(length, duration);

		logger.info("GET " + f.getName() + " " + "from " + request.getRemoteHost() + " | "
				+ FileUtils.byteCountToDisplaySize(length) + " | " + FileUtils.byteCountToDisplaySize(f.length())
				+ " | " + DurationFormatUtils.formatDurationHMS(duration.toMillis()) + " | "
				+ new DecimalFormat("###.##").format(rate) + " MB/s");
		
		if (request instanceof Request) {
			Request jettyRequest = (Request) request;
	
			logger.info("connection created " + (System.currentTimeMillis() - jettyRequest.getHttpChannel().getConnection().getCreatedTimeStamp()) + " ms ago" + 
//			", request complete " + jettyRequest.getHttpChannel().isRequestCompleted() + 
//			", response complte " + jettyRequest.getHttpChannel().isResponseCompleted() +  
			", channel state " + jettyRequest.getHttpChannel().getState() + 
			", channel persistent " + jettyRequest.getHttpChannel().isPersistent());
		}
		
		
	}

	private String getToken(HttpServletRequest request) {

		String tokenParameter = request.getParameter(TokenRequestFilter.QUERY_PARAMETER_TOKEN);
		String tokenHeader = request.getHeader(TokenRequestFilter.HEADER_AUTHORIZATION);

		return TokenRequestFilter.getToken(tokenHeader, tokenParameter);
	}

	private Path parsePath(String pathInfo) {
		// parse path
		String[] path = pathInfo.split("/");

		if (path.length != 5) {
			throw new NotFoundException();
		}

		if (!"".equals(path[0])) {
			throw new BadRequestException("path doesn't start with slash");
		}

		if (!"sessions".equals(path[1])) {
			throw new NotFoundException(path[1] + " not found");
		}

		UUID sessionId = UUID.fromString(path[2]);

		if (!"datasets".equals(path[3])) {
			throw new NotFoundException(path[3] + " not found");
		}

		UUID datasetId = UUID.fromString(path[4]);

		return new Path(sessionId, datasetId);
	}

	public static class Path {
		private UUID datasetId;
		private UUID sessionId;

		public Path(UUID sessionId, UUID datasetId) {
			this.sessionId = sessionId;
			this.datasetId = datasetId;
		}

		public UUID getDatasetId() {
			return datasetId;
		}

		public void setDatasetId(UUID datasetId) {
			this.datasetId = datasetId;
		}

		public UUID getSessionId() {
			return sessionId;
		}

		public void setSessionId(UUID sessionId) {
			this.sessionId = sessionId;
		}
	}

	private Dataset getDataset(UUID sessionId, UUID datasetId, String userToken, boolean requireReadWrite)
			throws RestException, IOException {

		// check authorization
		SessionDbClient sessionDbWithUserCredentials = new SessionDbClient(sessionDbUri, sessionDbEventsUri,
				new StaticCredentials("token", userToken));

		try {
			Dataset dataset = sessionDbWithUserCredentials.getDataset(sessionId, datasetId, requireReadWrite);

			if (dataset == null) {
				throw new ForbiddenException("dataset not found");
			}

			return dataset;

		} catch (RestException e) {
			int statusCode = e.getResponse().getStatus();
			String msg = e.getMessage();
			if (statusCode == HttpServletResponse.SC_FORBIDDEN) {
				throw new ForbiddenException(msg);
			} else if (statusCode == HttpServletResponse.SC_UNAUTHORIZED) {
				throw new NotAuthorizedException(msg);
			} else if (statusCode == HttpServletResponse.SC_NOT_FOUND) {
				throw new NotFoundException(msg);
			} else {
				throw e;
			}
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

	private File getStorageFile(UUID fileId) {
		return getStoragePath(storageRoot.toPath(), fileId).toFile();
	}

	public static java.nio.file.Path getStoragePath(java.nio.file.Path storage, UUID fileId) {

		// having a fileId as UUID makes sure that it doesn't point to other dirs
		String fileName = fileId.toString();

		String partitionDirName = fileName.substring(0, partitionLength);
		java.nio.file.Path partitionDir = storage.resolve(partitionDirName);
		if (!Files.exists(partitionDir)) {
			try {
				Files.createDirectory(partitionDir);
			} catch (IOException e) {
				// there is a race condition between the exists() and createDirectory() if this
				// is used from several threads
				// log the error, but try to use the dir anyway
				logger.error("failed to create dir " + partitionDir, e);
			}
		}
		return partitionDir.resolve(fileName);
	}

	private static Long getParameterLong(HttpServletRequest req, String key) {
		String stringValue = req.getParameter(key);
		if (stringValue != null) {
			return Long.parseLong(stringValue);
		}
		return null;
	}

	@Override
	protected void doPut(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (logger.isDebugEnabled()) {
			logger.info("PUT request for " + request.getRequestURI());
		}

		logger.info("PUT " + request.getRequestURI());
		// get query parameters
		Long chunkNumber = getParameterLong(request, "flowChunkNumber");
		Long chunkSize = getParameterLong(request, "flowChunkSize");
		Long flowTotalChunks = getParameterLong(request, "flowTotalChunks");

		// user's token set by TokenServletFilter
		String userToken = getToken(request);

		UUID fileId = null;
		Dataset dataset = null;

		try {
			logger.debug("chunkNumber " + chunkNumber + " uploading");

			Path path = parsePath(request.getPathInfo());

			// check authorization
			dataset = getDataset(path.getSessionId(), path.getDatasetId(), userToken, true);

			InputStream inputStream = request.getInputStream();

			if (chunkNumber == null || chunkNumber == 1) {
				if (dataset.getFile() != null) {
					throw new ConflictException("file not null");
				}

				// create a new file
				fileId = RestUtils.createUUID();
				File f = getStorageFile(fileId);
				if (f.exists()) {
					throw new ConflictException("file exists");
				}

				try {
					IOUtils.copy(inputStream, f);

					fi.csc.chipster.sessiondb.model.File file = new fi.csc.chipster.sessiondb.model.File();
					file.setFileId(fileId);
					file.setSize(f.length());
					file.setFileCreated(Instant.now());
					dataset.setFile(file);
					sessionDbClient.updateDataset(path.getSessionId(), dataset);
				} catch (EOFException e) {
					// upload interrupted
					f.delete();
					throw new BadRequestException("EOF");
				}
			} else {

				if (dataset.getFile() == null) {
					throw new ConflictException("file is null");
				}

				// append to the old file
				fileId = dataset.getFile().getFileId();
				File f = getStorageFile(fileId);

				if (f.exists()) {
					if (chunkNumber == null || chunkSize == null) {
						throw new ConflictException("missing query parameters");
					} else {
						if (isChunkReady(f, chunkNumber, chunkSize)) {
							// we have this junk already
							inputStream.close();
							response.setStatus(HttpServletResponse.SC_OK);
							return;
						}
					}
				}

				/*
				 * When the upload is paused, the chunk will end prematurely with EOF. If we
				 * append this stream directly to the right file, it will be difficult to
				 * continue later. Write the chunk first to a temp file and append to the right
				 * file only when the whole chunk is completed.
				 */

				File chunkFile = new File(f.getParent(), f.getName() + ".chunk" + chunkNumber);
				try {

					logger.debug("file size at start: " + f.length());

					// copy first to a temp file
					try (FileOutputStream chunkOutStream = new FileOutputStream(chunkFile, false)) {
						IOUtils.copy(inputStream, chunkOutStream);
					} finally {
						inputStream.close();
					}

					logger.debug("chunk file size: " + chunkFile.length());

					// append chunk to the right file
					try (FileInputStream chunkInStream = new FileInputStream(chunkFile)) {
						try (FileOutputStream outStream = new FileOutputStream(f, true)) {
							IOUtils.copy(chunkInStream, outStream);
						}
					}

					logger.debug("file size after copy: " + f.length());

					// // alternative implementation which buffers the chunk in memory
					// // the last chunk may be double the size of the chunkSize
					// ByteArrayOutputStream chunkOutStream = new
					// ByteArrayOutputStream(100*1024*1024);
					// IOUtils.copy(inputStream, chunkOutStream);
					//
					// // append chunk to the right file
					// FileOutputStream outStream = new FileOutputStream(f, true);
					// chunkOutStream.writeTo(outStream);
					// chunkOutStream.close();
					// outStream.close();

					// update the file size
					if (flowTotalChunks == chunkNumber) {
						dataset.getFile().setSize(f.length());
						sessionDbClient.updateDataset(path.getSessionId(), dataset);
					}

				} catch (EOFException e) {
					// upload interrupted
					logger.warn("put failed", e);
					throw new BadRequestException("EOF");
				} finally {
					if (chunkFile.exists()) {
						chunkFile.delete();
					}
				}
			}

			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
			return;
		} catch (IOException | RestException e) {
			logger.error("PUT failed " + e.getMessage());
			throw new InternalServerErrorException("upload failed", e);
		} catch (Exception e) {
			logger.error("PUT failed " + e.getMessage());
			throw e;
		}
	}

	private boolean isChunkReady(File f, Long chunkNumber, Long chunkSize) {
		long expectedSize = chunkNumber * chunkSize;
		logger.debug("is chunk " + chunkNumber + " ready? File size: " + f.length() + " expected " + expectedSize);
		return f.exists() && f.length() >= expectedSize;
	}

	@Override
	public void onEvent(SessionEvent e) {
		logger.debug("received a file event: " + e.getResourceType() + " " + e.getType());
		if (ResourceType.FILE == e.getResourceType()) {
			if (EventType.DELETE == e.getType()) {
				if (e.getResourceId() != null) {
					getStorageFile(e.getResourceId()).delete();
				} else {
					logger.warn("received a file deletion event with null id");
				}
			}
		}
	}

	private double getTransferRate(long fileSize, Duration duration) {
		double rate;
		if (duration.toMillis() != 0) {
			rate = ((double) fileSize) / duration.toMillis() * 1000 / 1024 / 1024;
		} else {
			rate = 0;
		}

		return rate;
	}

	@Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	}

	@Override
	protected void doHead(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	};

	@Override
	protected void doOptions(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		// CORS preflight checks require an ok response for OPTIONS
		response.setStatus(HttpServletResponse.SC_OK);
	};

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	};

	@Override
	protected void doTrace(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	};
}
