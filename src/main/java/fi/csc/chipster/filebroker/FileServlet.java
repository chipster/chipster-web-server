package fi.csc.chipster.filebroker;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

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
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.servlet.DefaultServlet;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.ConflictException;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.microarray.util.IOUtils;

/**
* <p>Servlet for serving and uploading files. Extends DefaultServlet and adds support for HTTP PUT method. 
* Also adds Chipster authentication and security checks.</p> 
* 
* <p>It would be easier to parse the path and query parameters with JAX-RS (Jersey), but it
* doesn't support HTTP Range queries, not even when running in Jetty. Implementing range queries efficiently 
* isn't trivial, but luckily DefaultServlet supports range queries by default.</p>
*/
public class FileServlet extends DefaultServlet implements SessionEventListener {

	private static final Logger logger = LogManager.getLogger();
	
	// specify whether get and put requests are logged
	// using jetty debug logging is not very useful as it is so verbose
	private boolean logRest = false;

	private File storageRoot;

	private SessionDbClient sessionDbClient;

	public FileServlet(File storageRoot, SessionDbClient sessionDbClient) {

		this.storageRoot = storageRoot;
		this.sessionDbClient = sessionDbClient;
		
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
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		if (logger.isDebugEnabled()) {
			logger.debug("RESTful file access: GET request for " + request.getRequestURI());
		}
		
		try {
			
			// get query parameters
			boolean download = request.getHeader("download") != null;
			boolean type = request.getHeader("type") != null;
		
			// username set by TokenServletFilter
			String username = request.getUserPrincipal().getName();		
		
			
			Path path = parsePath(request.getPathInfo());
			
			Dataset dataset = getDataset(path.getSessionId(), path.getDatasetId(), username, false);
					
			if (dataset.getFile() == null || dataset.getFile().getFileId() == null) {
				throw new ForbiddenException("file id is null");
			}
			
			UUID fileId = dataset.getFile().getFileId();		
		
			// get the file
			
			File f = getStorageFile(fileId);
			
		    if (!f.exists()) {
		    	throw new ForbiddenException("no such file");
		    } 
	    
		    RewrittenRequest rewrittenRequest = new RewrittenRequest(request, "/" + fileId.toString());
		    
		    if (download) {
	    		// hint filename for dataset export
		    	RestUtils.configureForDownload(response, dataset.getName());
		    }
		    
		    if (type) {
		    	// rendenring a html file in an iFrame requires the Content-Type header 
		    	response.setContentType(getType(dataset).toString());
		    }
		    		   		    
			// delegate to super class
			LocalDateTime before = LocalDateTime.now();
			super.doGet(rewrittenRequest, response);
			LocalDateTime after = LocalDateTime.now();

			// log performance
			Duration duration = Duration.between(before, after);
			double rate = getTransferRate(f.length(), duration);
			if (logRest) {
				logger.info("GET " + f.getName()  + " " + 
						"from " + request.getRemoteHost() + " | " +
						FileUtils.byteCountToDisplaySize(f.length()) + " | " + 
						DurationFormatUtils.formatDurationHMS(duration.toMillis()) + " | " +
						new DecimalFormat("###.##").format(rate) + " MB/s");
			}
			
		} catch (RestException e) {
			throw new ServletException("failed to get the dataset", e);
		}			    
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

	private Dataset getDataset(UUID sessionId, UUID datasetId, String username, boolean requireReadWrite) throws RestException {   			
		
		// check authorization
		
		Dataset dataset = sessionDbClient.getDataset(username, sessionId, datasetId, requireReadWrite);
		
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

	private File getStorageFile(UUID fileId) {
		// having a fileId as UUID makes sure that it doesn't point to other dirs
	    return new File(storageRoot, fileId.toString());	    
	}

	private static Long getParameterLong(HttpServletRequest req, String key) {
		String stringValue = req.getParameter(key);
		if (stringValue != null) {
			return Long.parseLong(stringValue);
		}
		return null;
	}
	
	@Override
	protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
			
		if (logger.isDebugEnabled()) {
			logger.info("PUT request for " + request.getRequestURI());
		}
		
		// get query parameters
		Long chunkNumber = getParameterLong(request, "flowChunkNumber");
		Long chunkSize = getParameterLong(request, "flowChunkSize");
		Long flowTotalChunks = getParameterLong(request, "flowTotalChunks");
		
		// username set by TokenServletFilter
		String username = request.getUserPrincipal().getName();		
					
		UUID fileId = null;
		Dataset dataset = null;
	
		try {
			logger.debug("chunkNumber " + chunkNumber + " uploading");			
						
			Path path = parsePath(request.getPathInfo());
			
			// check authorization
			dataset = getDataset(path.getSessionId(), path.getDatasetId(), username, true);
			
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
					file.setCreated(LocalDateTime.now());
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
				 * When the upload is paused, the chunk will end prematurely 
				 * with EOF. If we append this stream directly to the right
				 * file, it will be difficult to continue later. Write the
				 * chunk first to a temp file and append to the right file only
				 * when the whole chunk is completed.
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
					
	//				// alternative implementation which buffers the chunk in memory
	//				// the last chunk may be double the size of the chunkSize
	//				ByteArrayOutputStream chunkOutStream = new ByteArrayOutputStream(100*1024*1024);					
	//				IOUtils.copy(inputStream, chunkOutStream);					
	//
	//				// append chunk to the right file
	//				FileOutputStream outStream = new FileOutputStream(f, true);					
	//				chunkOutStream.writeTo(outStream);
	//				chunkOutStream.close();
	//				outStream.close();
					
					// update the file size
					if (flowTotalChunks == chunkNumber) {
						dataset.getFile().setSize(f.length());					
						sessionDbClient.updateDataset(path.getSessionId(), dataset);
					}
										
				} catch (EOFException e) {
					// upload interrupted 
					throw new BadRequestException("EOF");
				} finally {
					if (chunkFile.exists()) {
						chunkFile.delete();
					}
				}
			}			
			
			response.setStatus(HttpServletResponse.SC_OK);
			return;		
		} catch (IOException | RestException e) {
			throw new InternalServerErrorException("upload failed", e);
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
		if (duration.toMillis() != 0 ) {
			rate = ((double)fileSize) / duration.toMillis()*1000/1024/1024;
		} else {
			rate = 0;
		}

		return rate;
	}
	
	@Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	}
	
	@Override
	protected void doHead(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	};
	
	@Override
	protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// CORS preflight checks require an ok response for OPTIONS
		response.setStatus(HttpServletResponse.SC_OK);
	};
	
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	};
	
	@Override
	protected void doTrace(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	};
}

