package fi.csc.chipster.filebroker;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.UUID;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

@Path("sessions")
public class FileResource implements SessionEventListener {
	
	private static Logger logger = LogManager.getLogger();
	
	private static final String SESSION_ID = "sessionId";
	private static final String DATASET_ID = "fileId";

	private File storage;
	private SessionDbClient sessionDbClient;
		
	public FileResource(File storage, SessionDbClient sessionDbClient) {
		this.storage = storage;
		this.sessionDbClient = sessionDbClient;
	}
	
	@GET
	@Path("{" + SESSION_ID + "}/datasets/{" + DATASET_ID + "}")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response get(
			@PathParam(SESSION_ID) UUID sessionId, 
			@PathParam(DATASET_ID) UUID datasetId, 
			@DefaultValue("true") @QueryParam("download") boolean download,
			@DefaultValue("false") @QueryParam("type") boolean type,
			@Context SecurityContext sc) {
		
		try {
			
			// check authorization
			
			Dataset dataset = sessionDbClient.getDataset(sc.getUserPrincipal().getName(), sessionId, datasetId, false);
			
			if (dataset == null) {
				throw new ForbiddenException("dataset not found");
			}
			
			if (dataset.getFile() == null || dataset.getFile().getFileId() == null) {
				throw new NotFoundException("file id is null");
			}
			
			UUID fileId = dataset.getFile().getFileId();		
		
			// get the file
			
			File f = getStorageFile(fileId);
		
		    if (!f.exists()) {
		        throw new NotFoundException("no such file");
		    }
		
		    ResponseBuilder response = Response.ok(f);
		    
		    if (download) {
	    		// hint filename for dataset export
	    		response.header("Content-Disposition", "attachment; filename=\"" + dataset.getName() + "\"");
		    }
		    
		    if (type) {
		    	// rendenring a html file in an iFrame requires the Content-Type header 
		    	response.type(getType(dataset));
		    }
		    
		    return response.build();
	    
		} catch (RestException e) {
			throw new InternalServerErrorException("failed to get the dataset", e);
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
		// having a fileId as UUID makes sure that it doesn't point to other dirs
	    return new File(storage, fileId.toString());	    
	}

	@PUT
	@Path("{" + SESSION_ID + "}/datasets/{" + DATASET_ID + "}")
	//@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	public Response putFile(
			@PathParam(SESSION_ID) UUID sessionId, 
			@PathParam(DATASET_ID) UUID datasetId, 
			InputStream inputStream, 
			@QueryParam("flowChunkNumber") Long chunkNumber, 
			@QueryParam("flowChunkSize") Long chunkSize,
			@Context SecurityContext sc) {
						
		UUID fileId = null;
		Dataset dataset = null;
		
		try {
			logger.debug("chunkNumber " + chunkNumber + " uploading");			
			
			// check authorization
			dataset = getDataset(sessionId, datasetId, sc);
			
			if (dataset == null) {
				throw new ForbiddenException("dataset not found");
			}			
			
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
					sessionDbClient.updateDataset(sessionId, dataset);					
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
							return Response.ok().build();
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
					
					// copy first to a temp file
					FileOutputStream chunkOutStream = new FileOutputStream(chunkFile, false);
					
					IOUtils.copy(inputStream, chunkOutStream);					

					// append chunk to the right file
					FileInputStream chunkInStream = new FileInputStream(chunkFile);
					FileOutputStream outStream = new FileOutputStream(f, true);					
					IOUtils.copy(chunkInStream, outStream);
					chunkFile.delete();
					logger.debug("file size after copy: " + f.length());
					
//					// alternative implementation which buffers the chunk in memory
//					// the last chunk may be double the size of the chunkSize
//					ByteArrayOutputStream chunkOutStream = new ByteArrayOutputStream(100*1024*1024);					
//					IOUtils.copy(inputStream, chunkOutStream);					
//
//					// append chunk to the right file
//					FileOutputStream outStream = new FileOutputStream(f, true);					
//					chunkOutStream.writeTo(outStream);
//					chunkOutStream.close();
//					outStream.close();
										
				} catch (EOFException e) {
					// upload interrupted 
					chunkFile.delete();
					throw new BadRequestException("EOF");
				}
			}			
			
			return Response.ok().build();		
		} catch (IOException | RestException | InterruptedException e) {
			throw new InternalServerErrorException("upload failed", e);
		}
	}

	private boolean isChunkReady(File f, Long chunkNumber, Long chunkSize) {
		long expectedSize = chunkNumber * chunkSize;
		logger.debug("is chunk " + chunkNumber + " ready? File size: " + f.length() + " expected " + expectedSize);
		return f.exists() && f.length() >= expectedSize;
	}

	private Dataset getDataset(UUID sessionId, UUID datasetId, SecurityContext sc) throws InterruptedException, RestException {
			return sessionDbClient.getDataset(sc.getUserPrincipal().getName(), sessionId, datasetId, true);
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
}
