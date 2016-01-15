package fi.csc.chipster.sessiondb.resource;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.SessionDb;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;

public class DatasetResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	final private UUID sessionId;

	private SessionResource sessionResource;

	public DatasetResource() {
		sessionId = null;
	}
	
	public DatasetResource(SessionResource sessionResource, UUID id) {
		this.sessionResource = sessionResource;
		this.sessionId = id;
	}
	
    // CRUD
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response get(@PathParam("id") UUID datasetId, @Context SecurityContext sc) {
    	
    	// checks authorization
    	Session session = sessionResource.getSessionForReading(sc, sessionId);
    	Dataset result = session.getDatasets().get(datasetId);
    	
    	if (result == null) {
    		throw new NotFoundException();
    	}

   		return Response.ok(result).build();
    }

	@GET
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAll(@Context SecurityContext sc) {

		Collection<Dataset> result = sessionResource.getSessionForReading(sc, sessionId).getDatasets().values();

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(result)).build();		
    }	

	@POST
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response post(Dataset dataset, @Context UriInfo uriInfo, @Context SecurityContext sc) {	
    	        					
		if (dataset.getDatasetId() != null) {
			throw new BadRequestException("dataset already has an id, post not allowed");
		}
		
		UUID id = RestUtils.createUUID();
		dataset.setDatasetId(id);
		
		Session session = sessionResource.getSessionForWriting(sc, sessionId);
		
		checkFileModification(dataset);
		session.getDatasets().put(id, dataset);

		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();
		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.DATASET, id, EventType.CREATE));
		return Response.created(uri).build();
    }

	private void checkFileModification(Dataset dataset) {
		// if the file exists, don't allow it to be modified 
		if (dataset.getFile() == null || dataset.getFile().isEmpty()) {
			return;
		}
		File dbFile = getHibernate().session().get(File.class, dataset.getFile().getFileId());
		if (dbFile != null) {
			if (!dbFile.equals(dataset.getFile())) {
				throw new ForbiddenException("modification of existing file is forbidden");
			}
			dataset.setFile(dbFile);
		}
	}

	@PUT
	@Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response put(Dataset requestDataset, @PathParam("id") UUID datasetId, @Context SecurityContext sc) {
				    		
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		requestDataset.setDatasetId(datasetId);
		
		/*
		 * Checks that
		 * - user has write authorization for the session
		 * - the session contains this dataset
		 */
		if (!sessionResource.getSessionForWriting(sc, sessionId).getDatasets().containsKey(datasetId)) {
			throw new NotFoundException("dataset doesn't exist");
		}
		checkFileModification(requestDataset);
		getHibernate().session().merge(requestDataset);

		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.DATASET, datasetId, EventType.UPDATE));
		return Response.noContent().build();
    }

	@DELETE
    @Path("{id}")
	@Transaction
    public Response delete(@PathParam("id") UUID datasetId, @Context SecurityContext sc) {

		deleteDataset(sessionId, datasetId, sc, sessionResource);

		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.DATASET, datasetId, EventType.DELETE));
		return Response.noContent().build();
    }

	public static void deleteDataset(UUID sessionId, UUID datasetId, SecurityContext sc, SessionResource sessionResource) {
		// checks authorization
		Map<UUID, Dataset> sessionDatasets = sessionResource.getSessionForWriting(sc, sessionId).getDatasets();
		
		if (!sessionDatasets.containsKey(datasetId)) {
			throw new NotFoundException("dataset not found");
		}
		
		// remove from session, hibernate will take care of the actual dataset table
		Dataset dataset = sessionDatasets.remove(datasetId);
		
		if (dataset.getFile() != null && dataset.getFile().getFileId() != null) {
			UUID fileId = dataset.getFile().getFileId();		
			
			@SuppressWarnings("unchecked")
			List<Dataset> fileDatasets =  sessionResource.getHibernate().session()
					.createQuery("from Dataset where file=:file")
					.setParameter("file", dataset.getFile())
					.list();
			
			// don't care about the dataset that is being deleted
			// why do we still see it?
			fileDatasets.remove(dataset);

			// there isn't anymore anyone using this file and the file-broker
			// can delete it
			if (fileDatasets.isEmpty()) {
				// only for file-broker
				sessionResource.publish(SessionDb.FILES_TOPIC, new SessionEvent(sessionId, ResourceType.FILE, fileId, EventType.DELETE));
			}
		}
	}

	/**
	 * Make a collection compatible with JSON conversion
	 * 
	 * Default Java collections don't define the XmlRootElement annotation and 
	 * thus can't be converted directly to JSON. 
	 * @param <T>
	 * 
	 * @param result
	 * @return
	 */
	public static GenericEntity<Collection<Dataset>> toJaxbList(Collection<Dataset> result) {
		return new GenericEntity<Collection<Dataset>>(result) {};
	}
	
	private HibernateUtil getHibernate() {
		return sessionResource.getHibernate();
	}
}
