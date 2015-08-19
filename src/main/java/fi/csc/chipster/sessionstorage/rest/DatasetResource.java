package fi.csc.chipster.sessionstorage.rest;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.logging.Logger;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
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
import fi.csc.chipster.rest.hibernate.Hibernate;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessionstorage.model.Dataset;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.chipster.sessionstorage.model.SessionEvent;
import fi.csc.chipster.sessionstorage.model.SessionEvent.EventType;

public class DatasetResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(DatasetResource.class.getName());
	
	final private String sessionId;

	private SessionResource sessionResource;
	
	public DatasetResource() {
		sessionId = null;
	}
	
	public DatasetResource(SessionResource sessionResource, String id) {
		this.sessionResource = sessionResource;
		this.sessionId = id;
	}
	
    // CRUD
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response get(@PathParam("id") String datasetId, @Context SecurityContext sc) {
    	
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
    	        			
		dataset = RestUtils.getRandomDataset();
		dataset.setDatasetId(null);
		
		if (dataset.getDatasetId() != null) {
			throw new BadRequestException("session already has an id, post not allowed");
		}

		String id = RestUtils.createId();
		dataset.setDatasetId(id);

		sessionResource.getSessionForWriting(sc, sessionId).getDatasets().put(id, dataset);

		URI uri = uriInfo.getAbsolutePathBuilder().path(id).build();
		Events.broadcast(new SessionEvent(id, EventType.CREATE));
		return Response.created(uri).build();
    }

	@PUT
	@Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response put(Dataset requestDataset, @PathParam("id") String datasetId, @Context SecurityContext sc) {
				    		
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

		getHibernate().session().merge(requestDataset);

		// more fine-grained events are needed, like "job added" and "dataset removed"
		Events.broadcast(new SessionEvent(datasetId, EventType.UPDATE));
		return Response.noContent().build();
    }

	@DELETE
    @Path("{id}")
	@Transaction
    public Response delete(@PathParam("id") String datasetId, @Context SecurityContext sc) {

		// checks authorization
		Map<String, Dataset> datasets = sessionResource.getSessionForWriting(sc, sessionId).getDatasets();
		
		if (!datasets.containsKey(datasetId)) {
			throw new NotFoundException("dataset not found");
		}
		
		// remove from session, hibernate will take care of the actual dataset table
		datasets.remove(datasetId);

		Events.broadcast(new SessionEvent(datasetId, EventType.DELETE));
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
	
	private Hibernate getHibernate() {
		return sessionResource.getHibernate();
	}
}
