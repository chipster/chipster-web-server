package fi.csc.chipster.sessionstorage.rest;

import java.net.URI;
import java.util.List;
import java.util.logging.Logger;

import javax.inject.Singleton;
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
import javax.ws.rs.core.UriInfo;

import org.hibernate.ObjectNotFoundException;

import fi.csc.chipster.sessionstorage.model.Dataset;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.chipster.sessionstorage.model.SessionEvent;
import fi.csc.chipster.sessionstorage.model.SessionEvent.EventType;

@Singleton
public class DatasetResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(DatasetResource.class.getName());
	
	final private String sessionId;
	
	public DatasetResource() {
		sessionId = null;
	}
	
	public DatasetResource(String id) {
		sessionId = id;
	}
	
    // CRUD
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") String id) {
    	Hibernate.beginTransaction();
    	Dataset result = (Dataset) Hibernate.session().get(Dataset.class, id);
    	Hibernate.commit();
    	
    	if (result == null) {
    		throw new NotFoundException();
    	}

   		return Response.ok(result).build();
    }
    
	@GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAll() {

		Hibernate.beginTransaction();				
		List<Dataset> result = getSession().getDatasets();
		result.size(); // trigger lazy loading before the transaction is closed
		Hibernate.commit();

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(result)).build();
    }	

	@POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response post(Dataset dataset, @Context UriInfo uriInfo) {	
    	        	
		dataset = RestUtils.getRandomDataset();
		dataset.setId(null);
		
		if (dataset.getId() != null) {
			throw new BadRequestException("session already has an id, post not allowed");
		}

		
		dataset.setId(RestUtils.createId());

		Hibernate.beginTransaction();	
		getSession().getDatasets().add(dataset);
		Hibernate.commit();

		URI uri = uriInfo.getAbsolutePathBuilder().path(dataset.getId()).build();
		Events.broadcast(new SessionEvent(dataset.getId(), EventType.CREATE));
		return Response.created(uri).build();
    }

	@PUT
	@Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response put(Dataset dataset, @PathParam("id") String id) {
				    		
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		dataset.setId(id);

		Hibernate.beginTransaction();
		if (Hibernate.session().get(Dataset.class, id) == null) {
			// transaction will commit, but we haven't changed anything
			return Response.status(Status.NOT_FOUND)
					.entity("dataset doesn't exist").build();
		}
		Hibernate.session().merge(dataset);
		Hibernate.commit();

		// more fine-grained events are needed, like "job added" and "dataset removed"
		Events.broadcast(new SessionEvent(id, EventType.UPDATE));
		return Response.noContent().build();
    }

	@DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String datasetId) {

		try {
			// remove from session, hibernate will take care of the actual dataset table
			Hibernate.beginTransaction();
			Dataset dataset = (Dataset) Hibernate.session().load(Dataset.class, datasetId);
			getSession().getDatasets().remove(dataset);
			Hibernate.commit();

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
		return (Session) Hibernate.session().load(Session.class, sessionId);
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
}
