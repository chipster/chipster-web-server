package fi.csc.chipster.sessionstorage.resource;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

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

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessionstorage.model.Dataset;
import fi.csc.chipster.sessionstorage.model.File;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.chipster.sessionstorage.model.SessionEvent;
import fi.csc.chipster.sessionstorage.model.SessionEvent.EventType;
import fi.csc.chipster.sessionstorage.model.SessionEvent.ResourceType;

public class DatasetResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(DatasetResource.class.getName());
	
	final private UUID sessionId;

	private SessionResource sessionResource;

	private Events events;
	
	public DatasetResource() {
		sessionId = null;
	}
	
	public DatasetResource(SessionResource sessionResource, UUID id, Events events) {
		this.sessionResource = sessionResource;
		this.sessionId = id;
		this.events = events;
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
		events.broadcast(new SessionEvent(sessionId, ResourceType.DATASET, id, EventType.CREATE));
		return Response.created(uri).build();
    }

	private void checkFileModification(Dataset dataset) {
		// if the file exists, don't allow it to be modified 
		File file = getHibernate().session().get(File.class, dataset.getFile().getFileId());
		if (file != null) {
			if (!file.equals(dataset.getFile())) {
				throw new ForbiddenException("modification of existing file is forbidden");
			}
			dataset.setFile(file);
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

		// more fine-grained events are needed, like "job added" and "dataset removed"
		events.broadcast(new SessionEvent(sessionId, ResourceType.DATASET, datasetId, EventType.UPDATE));
		return Response.noContent().build();
    }

	@DELETE
    @Path("{id}")
	@Transaction
    public Response delete(@PathParam("id") UUID datasetId, @Context SecurityContext sc) {

		// checks authorization
		Map<UUID, Dataset> datasets = sessionResource.getSessionForWriting(sc, sessionId).getDatasets();
		
		if (!datasets.containsKey(datasetId)) {
			throw new NotFoundException("dataset not found");
		}
		
		// remove from session, hibernate will take care of the actual dataset table
		datasets.remove(datasetId);

		events.broadcast(new SessionEvent(sessionId, ResourceType.DATASET, datasetId, EventType.DELETE));
		return Response.noContent().build();
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
	private GenericEntity<Collection<Dataset>> toJaxbList(Collection<Dataset> result) {
		return new GenericEntity<Collection<Dataset>>(result) {};
	}
	
	private HibernateUtil getHibernate() {
		return sessionResource.getHibernate();
	}
}
