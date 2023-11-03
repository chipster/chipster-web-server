package fi.csc.chipster.filestorage;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.InclusiveByteRange;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.servlet.DefaultServlet;

import fi.csc.chipster.archive.BackupUtils;
import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.UserToken;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filebroker.FileBrokerResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.ServletUtils;
import fi.csc.chipster.rest.exception.ConflictException;
import fi.csc.chipster.rest.exception.InsufficientStorageException;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.util.IOUtils;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;

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


	public static final String PATH_PUT_ALLOWED = "putAllowed";

	private static final String CONF_FILE_STORAGE_BACKUP_PRESERVE_SPACE = "file-storage-backup-preserve-space";
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

	private float backupPreserveSpace;

	private boolean isBackupEnabled;

	public FileServlet(File storageRoot, AuthenticationClient authService, Config config) {

		this.storageRoot = storageRoot;
		this.authService = authService;
		
		this.preserveSpace = config.getFloat(CONF_KEY_FILE_STORAGE_PRESERVE_SPACE);
		this.backupPreserveSpace = config.getFloat(CONF_FILE_STORAGE_BACKUP_PRESERVE_SPACE);
		this.isBackupEnabled = !BackupUtils.getBackupBucket(config, Role.FILE_STORAGE).isEmpty();

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

		if (("/" + PATH_FILES + "/" + PATH_PUT_ALLOWED).equals(request.getPathInfo())) {
			doGetPutAllowed(request, response);
		} else {
			doGetFile(request, response); 
		}
	}

	protected void doGetFile(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		
		try {
						
			if (logRest) {
				logger.info("GET " + request.getRequestURI());
			}
		
			allowOnlyFileBroker(request);
	
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
	
	private void allowOnlyFileBroker(HttpServletRequest request) {
		// user's token set by TokenServletFilter
		String tokenString = ServletUtils.getToken(request);
		UserToken token = authService.validateUserToken(tokenString);
		
		// check authorization
		if (!token.getRoles().contains(Role.FILE_BROKER)) {
			throw new NotAuthorizedException("wrong role");
		}
	}
	
	private void doGetPutAllowed(HttpServletRequest request, HttpServletResponse response) throws IOException {
		
		// get query parameters
		Long totalSize = getParameterLong(request, FileBrokerResource.FLOW_TOTAL_SIZE);

		allowOnlyFileBroker(request);
		
		checkDiskSpace(totalSize, request);
		
		response.setStatus(200);
		response.setHeader("Content-Type", "text/plain");
		PrintWriter out = response.getWriter();
		out.print("put is allowed");
		out.flush();   
	}

	@Override
	protected void doPut(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		
		// get query parameters
		Long chunkNumber = getParameterLong(request, FileBrokerResource.FLOW_CHUNK_NUMBER);
		Long chunkSize = getParameterLong(request, FileBrokerResource.FLOW_CHUNK_SIZE);
		@SuppressWarnings("unused")
		Long flowTotalChunks = getParameterLong(request, FileBrokerResource.FLOW_TOTAL_CHUNKS);

		Long totalSize = getParameterLong(request, FileBrokerResource.FLOW_TOTAL_SIZE);
		
		if (logRest) {
			
			String displaySize = "-";
			
			if (totalSize != null) {
				displaySize = FileUtils.byteCountToDisplaySize(totalSize);
			}
			
			logger.info("PUT " + request.getRequestURI() + " totalSize: " + displaySize + ", chunkNumber: " + chunkNumber);
		}

		allowOnlyFileBroker(request);
		
		try {
			logger.debug("chunkNumber " + chunkNumber + " uploading");

			UUID fileId = parsePath(request.getPathInfo());			

			if (chunkNumber == null || chunkNumber == 1) {
									
				InputStream inputStream = request.getInputStream();

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
				
				InputStream inputStream = request.getInputStream();

				if (f.exists()) {
					if (chunkNumber == null || chunkSize == null) {
						throw new ConflictException("missing query parameters");
					} else {
						if (isChunkReady(f, chunkNumber, chunkSize)) {
							// we have this chunk already							
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
		
		if (isBackupEnabled) {
			size = (long) (size * this.backupPreserveSpace);
		}
		
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
		
			this.allowOnlyFileBroker(request);
	
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
