package fi.csc.chipster.sessionstorage.rest;

import java.net.URI;
import java.util.List;
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
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.hibernate.ObjectNotFoundException;

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

    	checkDatasetReadAuthorization(sc.getUserPrincipal().getName(), sessionId, datasetId);

    	Dataset result = (Dataset) getHibernate().session().get(Dataset.class, datasetId);
    	
    	if (result == null) {
    		throw new NotFoundException();
    	}

   		return Response.ok(result).build();
    }

	@GET
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAll(@Context SecurityContext sc) {

		sessionResource.checkSessionReadAuthorization(sc.getUserPrincipal().getName(), sessionId);
		List<Dataset> result = getSession().getDatasets();
		result.size(); // trigger lazy loading before the transaction is closed

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

		
		dataset.setDatasetId(RestUtils.createId());

		sessionResource.checkSessionWriteAuthorization(sc.getUserPrincipal().getName(), sessionId);
		getSession().getDatasets().add(dataset);

		URI uri = uriInfo.getAbsolutePathBuilder().path(dataset.getDatasetId()).build();
		Events.broadcast(new SessionEvent(dataset.getDatasetId(), EventType.CREATE));
		return Response.created(uri).build();
    }

	@PUT
	@Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response put(Dataset dataset, @PathParam("id") String id, @Context SecurityContext sc) {
				    		
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		dataset.setDatasetId(id);

		if (getHibernate().session().get(Dataset.class, id) == null) {
			// transaction will commit, but we haven't changed anything
			return Response.status(Status.NOT_FOUND)
					.entity("dataset doesn't exist").build();
		}
		checkDatasetWriteAuthorization(sc.getUserPrincipal().getName(), sessionId, dataset.getDatasetId());
		getHibernate().session().merge(dataset);

		// more fine-grained events are needed, like "job added" and "dataset removed"
		Events.broadcast(new SessionEvent(id, EventType.UPDATE));
		return Response.noContent().build();
    }

	@DELETE
    @Path("{id}")
	@Transaction
    public Response delete(@PathParam("id") String datasetId, @Context SecurityContext sc) {

		try {
			// remove from session, hibernate will take care of the actual dataset table
			checkDatasetWriteAuthorization(sc.getUserPrincipal().getName(), sessionId, datasetId);
			Dataset dataset = (Dataset) getHibernate().session().load(Dataset.class, datasetId);
			getSession().getDatasets().remove(dataset);

			Events.broadcast(new SessionEvent(datasetId, EventType.DELETE));
			return Response.noContent().build();
		} catch (ObjectNotFoundException e) {
			return Response.status(Status.NOT_FOUND).build();
		}
    }

	/**
	 * Call inside a transaction
	 * 
	 * @return
	 */
	private Session getSession() {
		return (Session) getHibernate().session().load(Session.class, sessionId);
	}
	
	private void checkDatasetReadAuthorization(String username, String sessionId, String datasetId) {
		sessionResource.checkSessionReadAuthorization(username, sessionId);
		checkThatSessionContains(sessionId, datasetId);
	}
	
	private void checkDatasetWriteAuthorization(String username, String sessionId, String datasetId) {
		sessionResource.checkSessionWriteAuthorization(username, sessionId);
		checkThatSessionContains(sessionId, datasetId);
	}
	
	/**
	 * Critical part of authorization checks
	 * 
	 * The authorization is done in session level, so we must check on 
	 * each request that the dataset really is in the session. Otherwise user
	 * can bypass the checks by using her own sessionId. 
	 * 
	 * @param sessionId
	 * @param datasetId
	 */
    private void checkThatSessionContains(String sessionId, String datasetId) {
    	
		Session session = getHibernate().session().get(Session.class, sessionId);
		for (Dataset dataset : session.getDatasets()) {
			if (datasetId.equals(dataset.getDatasetId())) {
				return;
			}
		}
		throw new NotFoundException();
	}

	/**
	 * Make a list compatible with JSON conversion
	 * 
	 * Default Java collections don't define the XmlRootElement annotation and 
	 * thus can't be converted directly to JSON. 
	 * @param <T>
	 * 
	 * @param result
	 * @return
	 */
	private GenericEntity<List<Dataset>> toJaxbList(List<Dataset> result) {
		return new GenericEntity<List<Dataset>>(result) {};
	}
	
	private Hibernate getHibernate() {
		return sessionResource.getHibernate();
	}
}
