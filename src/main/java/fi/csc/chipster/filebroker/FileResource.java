package fi.csc.chipster.filebroker;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.RestUtils;
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
	public Response get(@PathParam(SESSION_ID) UUID sessionId, @PathParam(DATASET_ID) UUID datasetId, @Context SecurityContext sc) {
		
		// check authorization
		UUID fileId = getFileId(sc, sessionId, datasetId, false);		

		File f = getStorageFile(fileId);

	    if (!f.exists()) {
	        throw new NotFoundException("no such file");
	    }

	    return Response.ok(f).build();
	}
	
	private File getStorageFile(UUID fileId) {
		// having a fileId as UUID makes sure that it doesn't point to other dirs
	    return new File(storage, fileId.toString());	    
	}

	@PUT
	@Path("{" + SESSION_ID + "}/datasets/{" + DATASET_ID + "}")
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	public Response putFile(@PathParam(SESSION_ID) UUID sessionId, @PathParam(DATASET_ID) UUID datasetId, InputStream inputStream, @Context SecurityContext sc) {
				
		// check authorization
		Dataset dataset = getDataset(sc, sessionId, datasetId, true);
		
		if (dataset.getFile() != null) {
			return Response.status(Response.Status.CONFLICT).build();
		}
		
		UUID fileId = RestUtils.createUUID();
			
	    File f = getStorageFile(fileId);
	    
	    if (f.exists()) {
	        return Response.status(Response.Status.CONFLICT).build();
	    }
	    
		try {
			IOUtils.copy(inputStream, f);
			
			fi.csc.chipster.sessiondb.model.File file = new fi.csc.chipster.sessiondb.model.File();
			file.setFileId(fileId);
			dataset.setFile(file);			
			sessionDbClient.updateDataset(sessionId, dataset);
			
			return Response.ok().build();
		} catch (IOException e) {
			throw new InternalServerErrorException("upload failed", e);
		}
	}

	private UUID getFileId(SecurityContext sc, UUID sessionId, UUID datasetId, boolean requireReadWrite) {
		
		Dataset dataset = getDataset(sc, sessionId, datasetId, requireReadWrite);			
		
		if (dataset.getFile() == null || dataset.getFile().getFileId() == null) {
			throw new NotFoundException("file id is null");
		}
		
		return dataset.getFile().getFileId();
	}

	private Dataset getDataset(SecurityContext sc, UUID sessionId, UUID datasetId, boolean requireReadWrite) {
		Dataset dataset = sessionDbClient.getDataset(sc.getUserPrincipal().getName(), sessionId, datasetId, requireReadWrite);
		
		if (dataset == null) {
			throw new NotFoundException("dataset not found");
		}
		
		return dataset;
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
