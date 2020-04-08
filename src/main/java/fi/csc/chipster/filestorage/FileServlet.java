package fi.csc.chipster.filestorage;

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
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;

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
import fi.csc.chipster.auth.model.ParsedToken;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filebroker.FileBrokerResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.exception.ConflictException;
import fi.csc.chipster.rest.exception.InsufficientStorageException;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.util.IOUtils;

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
public class FileServlet extends DefaultServlet implements SessionEventListener {

	private static final String CONF_KEY_FILE_STORAGE_PRESERVE_SPACE = "file-storage-preserve-space";

	public static final String PATH_FILES = "files";

	private static final Logger logger = LogManager.getLogger();

	public static final int partitionLength = 2;

	public static final String HEADER_FILE_CONTENT_LENGTH = "File-Content-Length";

	// specify whether get and put requests are logged
	// using jetty debug logging is not very useful as it is so verbose
	private boolean logRest = false;

	private File storageRoot;

	private AuthenticationClient authService;

	private float preserveSpace;

	public FileServlet(File storageRoot, AuthenticationClient authService, Config config) {

		this.storageRoot = storageRoot;
		this.authService = authService;
		
		this.preserveSpace = config.getFloat(CONF_KEY_FILE_STORAGE_PRESERVE_SPACE);

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
		
		try {
						
			if (logRest) {
				logger.info("GET " + request.getRequestURI());
			}
		
			// user's token set by TokenServletFilter
			String tokenString = getToken(request);
			ParsedToken token = authService.validate(tokenString);
			
			// check authorization
			if (!token.getRoles().contains(Role.FILE_BROKER)) {
				throw new NotAuthorizedException("wrong role");
			}
	
			UUID fileId = parsePath(request.getPathInfo());
	
			// get the file
	
			java.nio.file.Path f = getStoragePath(storageRoot.toPath(), fileId);
	
			if (!Files.exists(f)) {
				throw new NotFoundException("no such file");
			}
	
			// remove "storage/" from the beginning
			java.nio.file.Path pathUnderStorage = storageRoot.toPath().relativize(f);
	
			RewrittenRequest rewrittenRequest = new RewrittenRequest(request, "/" + pathUnderStorage.toString());
		
			Instant before = Instant.now();						
	
			// delegate to super class
			super.doGet(rewrittenRequest, response);
			
			// log performance
			if (logRest) {
				logAsyncGet(request, response, f.toFile(), before);
			}
		} catch (IOException | ServletException e) {
			// make sure all errors are logged
			logger.error("GET error", e);
			throw e;
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
			logger.debug("sync request");
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

		logger.info("GET " + request.getRequestURI() + " " + "from " + request.getRemoteHost() + " | "
				+ FileUtils.byteCountToDisplaySize(length) + " | " + FileUtils.byteCountToDisplaySize(f.length())
				+ " | " + DurationFormatUtils.formatDurationHMS(duration.toMillis()) + " | "
				+ new DecimalFormat("###.##").format(rate) + " MB/s");
		
		if (request instanceof Request) {
			Request jettyRequest = (Request) request;
	
			if (logger.isDebugEnabled()) {
				logger.debug("connection created " + (System.currentTimeMillis() - jettyRequest.getHttpChannel().getConnection().getCreatedTimeStamp()) + " ms ago" + 
					", request complete " + jettyRequest.getHttpChannel().isRequestCompleted() + 
					", response complte " + jettyRequest.getHttpChannel().isResponseCompleted() +  
					", channel state " + jettyRequest.getHttpChannel().getState() + 
					", channel persistent " + jettyRequest.getHttpChannel().isPersistent());
			}
		}		
	}

	private String getToken(HttpServletRequest request) {

		String tokenParameter = request.getParameter(TokenRequestFilter.QUERY_PARAMETER_TOKEN);
		String tokenHeader = request.getHeader(TokenRequestFilter.HEADER_AUTHORIZATION);

		return TokenRequestFilter.getToken(tokenHeader, tokenParameter);
	}

	private UUID parsePath(String pathInfo) {
		// parse path
		String[] path = pathInfo.split("/");

		if (path.length != 3) {
			throw new NotFoundException();
		}

		if (!"".equals(path[0])) {
			throw new BadRequestException("path doesn't start with slash");
		}
		
		if (!PATH_FILES.equals(path[1])) {
			throw new NotFoundException("path not found");
		}

		return UUID.fromString(path[2]);
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
		
		if (logRest) {
			logger.info("PUT " + request.getRequestURI());
		}
		
		// get query parameters
		Long chunkNumber = getParameterLong(request, FileBrokerResource.FLOW_CHUNK_NUMBER);
		Long chunkSize = getParameterLong(request, FileBrokerResource.FLOW_CHUNK_SIZE);
		@SuppressWarnings("unused")
		Long flowTotalChunks = getParameterLong(request, FileBrokerResource.FLOW_TOTAL_CHUNKS);
		Long totalSize = getParameterLong(request, FileBrokerResource.FLOW_TOTAL_SIZE);

		// user's token set by TokenServletFilter
		String tokenString = getToken(request);
		ParsedToken token = authService.validate(tokenString);
		
		// check authorization
		if (!token.getRoles().contains(Role.FILE_BROKER)) {
			throw new NotAuthorizedException("wrong role");
		}
		
		try {
			logger.debug("chunkNumber " + chunkNumber + " uploading");

			UUID fileId = parsePath(request.getPathInfo());

			InputStream inputStream = request.getInputStream();

			if (chunkNumber == null || chunkNumber == 1) {
				
				checkDiskSpace(totalSize, request);

				// create a new file
				File f = getStorageFile(fileId);
				if (f.exists()) {
					throw new ConflictException("file exists");
				}

				try {
					IOUtils.copy(inputStream, f);
					response.setHeader(HEADER_FILE_CONTENT_LENGTH, "" + f.length());

				} catch (EOFException e) {
					// upload interrupted
					f.delete();
					logger.error("PUT cancelled " + e.getClass().getName() + " " + e.getMessage());
					throw new UploadCancelledException("EOF");
				}
			} else {

				// does parsePath check this already?
				if (fileId == null) {
					throw new ConflictException("file is null");
				}

				// append to the old file
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
							response.setHeader(HEADER_FILE_CONTENT_LENGTH, "" + f.length());
						}
					}

					logger.debug("file size after copy: " + f.length());
					
					response.setStatus(HttpServletResponse.SC_NO_CONTENT);

				} catch (EOFException e) {
					logger.info("upload paused in file-storage: " + e.getClass().getSimpleName() + " " + e.getMessage());					
					response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				} finally {
					if (chunkFile.exists()) {
						chunkFile.delete();
					}
				}
			}
		} catch (UploadCancelledException e) {
			// logged already
			throw e;
		} catch (IOException e) {
			logger.error("PUT failed " + e.getMessage());
			throw new InternalServerErrorException("upload failed", e);
		} catch (Exception e) {
			logger.error("PUT failed " + e.getMessage());
			throw e;
		}
	}

	private void checkDiskSpace(Long flowTotalSize, HttpServletRequest request) {
		
		long size;
		if (flowTotalSize != null) {
			size = flowTotalSize;
		} else {
			if (request.getContentLengthLong() != -1) {
				size = request.getContentLengthLong();
 			} else {
				logger.warn("put request doesn't have " + FileBrokerResource.FLOW_TOTAL_SIZE + " or content length header, cannot ensure disk space");
				size = 0;
 			}
		}
		
		long preserveBytes = (long) (storageRoot.getTotalSpace() * this.preserveSpace / 100);
		
		if (size + preserveBytes > storageRoot.getUsableSpace()) {
			throw new InsufficientStorageException("insufficient storage");
		} else {
			logger.debug("upload size: " + size + ", preserve size: " + preserveBytes + ", available: " + storageRoot.getUsableSpace());
		}
	}


	private boolean isChunkReady(File f, Long chunkNumber, Long chunkSize) {
		long expectedSize = chunkNumber * chunkSize;
		logger.debug("is chunk " + chunkNumber + " ready? File size: " + f.length() + " expected " + expectedSize);
		return f.exists() && f.length() >= expectedSize;
	}

	/**
	 * Delete file when it's deleted from the DB
	 * 
	 * Handle events here in file-storage, because there could be multiple file-brokers and it would be
	 * messy if all those would start making delete requests to the file-storage in parallel.
	 * 
	 * Unfortunately we don't know if the file was supposed to be in this file-storage replica, because
	 * the DB object has been deleted already.
	 *
	 */
	@Override
	public void onEvent(SessionEvent e) {
		logger.debug("received a file event: " + e.getResourceType() + " " + e.getType());
		if (ResourceType.FILE == e.getResourceType()) {
			if (EventType.DELETE == e.getType()) {
				if (e.getResourceId() != null) {
					File storageFile = getStorageFile(e.getResourceId());
					// otherwise probably just a file on some other file-storage replica
					if (storageFile.exists()) {
						storageFile.delete();
					}					
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

	/**
	 * Delete file
	 * 
	 * Only used in storage migrations, normally files are deleted based on session-db events.
	 */
	@Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		
		try {			
		
			// user's token set by TokenServletFilter
			String tokenString = getToken(request);
			ParsedToken token = authService.validate(tokenString);
			
			// check authorization
			if (!token.getRoles().contains(Role.FILE_BROKER)) {
				throw new NotAuthorizedException("wrong role");
			}
	
			UUID fileId = parsePath(request.getPathInfo());
			
			logger.info("delete file " + fileId);
	
			// get the file
			java.nio.file.Path f = getStoragePath(storageRoot.toPath(), fileId);
	
			if (!Files.exists(f)) {
				throw new NotFoundException("no such file");
			}
			
			Files.delete(f);

			response.setStatus(204);
			
		} catch (IOException e) {
			// make sure all errors are logged
			logger.error("DELETE error", e);
			throw e;
		}
	}

	@Override
	protected void doHead(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	};

	@Override
	protected void doOptions(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
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
