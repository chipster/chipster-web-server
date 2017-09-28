package fi.csc.chipster.sessiondb.resource;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
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
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.SessionDb;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.DatasetToken;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;

public class SessionDatasetResource {
	
	public static final String QUERY_PARAM_READ_WRITE = "read-write";

	private static Logger logger = LogManager.getLogger();
	
	final private UUID sessionId;

	private SessionResource sessionResource;

	public SessionDatasetResource() {
		sessionId = null;
	}
	
	public SessionDatasetResource(SessionResource sessionResource, UUID id) {
		this.sessionResource = sessionResource;
		this.sessionId = id;
	}
	
    // CRUD
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response get(@PathParam("id") UUID datasetId, @QueryParam(QUERY_PARAM_READ_WRITE) boolean requireReadWrite, @Context SecurityContext sc) {
    	
    	
    	// checks authorization
   		String userToken = ((AuthPrincipal)sc.getUserPrincipal()).getTokenKey();
    	sessionResource.getRuleTable().checkAuthorizationWithToken(userToken, sessionId, datasetId, requireReadWrite);
		
    	Dataset result = getDataset(datasetId, getHibernate().session());
    	
    	if (result == null) {
    		throw new NotFoundException();
    	}

   		return Response.ok(result).build();
    }
    
    public Dataset getDataset(UUID datasetId, org.hibernate.Session hibernateSession) {
    	return hibernateSession.get(Dataset.class, datasetId);
    }
    
	@GET
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAll(@Context SecurityContext sc) {

		Collection<Dataset> result = sessionResource.getRuleTable().getSessionForReading(sc, sessionId).getDatasets().values();

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(result)).build();
    }	

	@POST
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response post(Dataset dataset, @Context UriInfo uriInfo, @Context SecurityContext sc) {	
    	        					
		if (dataset.getDatasetId() != null) {
			throw new BadRequestException("dataset already has an id, post not allowed");
		}
		
		UUID id = RestUtils.createUUID();
		dataset.setDatasetId(id);

		Session session = sessionResource.getRuleTable().getSessionForWriting(sc, sessionId);
		
		// make sure a hostile client doesn't set the session
		dataset.setSession(session);
		
		if (dataset.getCreated() == null) {
			dataset.setCreated(LocalDateTime.now());
		}
		
		create(dataset, getHibernate().session());

		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();
		
		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("datasetId", id.toString());
		
		return Response.created(uri).entity(json).build();
    }
	
	public void create(Dataset dataset, org.hibernate.Session hibernateSession) {
		
		checkFileModification(dataset, hibernateSession);
		
		if (dataset.getFile() != null) {
			// why CascadeType.PERSIST isn't enough?
			hibernateSession.save(dataset.getFile());
		}
		hibernateSession.save(dataset);
		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.DATASET, dataset.getDatasetId(), EventType.CREATE), hibernateSession);
	}

	private void checkFileModification(Dataset dataset, org.hibernate.Session hibernateSession) {
		// if the file exists, don't allow it to be modified 
		if (dataset.getFile() == null || dataset.getFile().isEmpty()) {
			return;
		}
		File dbFile = hibernateSession.get(File.class, dataset.getFile().getFileId());
		if (dbFile != null) {
			if (!dbFile.equals(dataset.getFile())) {			
				throw new ForbiddenException("modification of existing file is forbidden");
			}
			dataset.setFile(dbFile);
		}		
	}

	@PUT
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
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
		Session session = sessionResource.getRuleTable().getSessionForWriting(sc, sessionId);
		Dataset dbDataset = getHibernate().session().get(Dataset.class, datasetId);
		if (dbDataset == null || dbDataset.getSession().getSessionId() != session.getSessionId()) {
			throw new NotFoundException("dataset doesn't exist");
		}
		
		if (!sc.isUserInRole(Role.FILE_BROKER)) {
			checkFileModification(requestDataset, getHibernate().session());
		}
		
		if (requestDataset.getFile() == null || requestDataset.getFile().isEmpty()) {
			// if the client doesn't care about the File, simply keep the db version
			requestDataset.setFile(dbDataset.getFile());
		}
		
		// make sure a hostile client doesn't set the session
		requestDataset.setSession(session);
		
		update(requestDataset, getHibernate().session());
		
		return Response.noContent().build();
    }
	
	public void update(Dataset dataset, org.hibernate.Session hibernateSession) {
		hibernateSession.merge(dataset);
		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.DATASET, dataset.getDatasetId(), EventType.UPDATE), hibernateSession);
	}

	@DELETE
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
    @Path("{id}")
	@Transaction
    public Response delete(@PathParam("id") UUID datasetId, @Context SecurityContext sc) {

		// checks authorization
		Session session = sessionResource.getRuleTable().getSessionForWriting(sc, sessionId);
		Dataset dataset = sessionResource.getHibernate().session().get(Dataset.class, datasetId);
		
		if (dataset == null || dataset.getSession().getSessionId() != session.getSessionId()) {
			throw new NotFoundException("dataset not found");
		}
		
		deleteDataset(dataset, getHibernate().session());

		return Response.noContent().build();
    }

	public void deleteDataset(Dataset dataset, org.hibernate.Session hibernateSession) {
		
		// clean up Dataset
		int rows = getHibernate().session()
				.createQuery("delete from " + DatasetToken.class.getSimpleName() + " where dataset=:dataset")
				.setParameter("dataset", dataset).executeUpdate();
		
		logger.debug("deleted " + rows + " dataset tokens");

		
		hibernateSession.delete(dataset);
		
		if (dataset.getFile() != null && dataset.getFile().getFileId() != null) {
			UUID fileId = dataset.getFile().getFileId();		
			
			@SuppressWarnings("unchecked")
			List<Dataset> fileDatasets =  hibernateSession
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
				sessionResource.publish(SessionDb.FILES_TOPIC, new SessionEvent(sessionId, ResourceType.FILE, fileId, EventType.DELETE), hibernateSession);
			}
			
		}
		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.DATASET, dataset.getDatasetId(), EventType.DELETE), hibernateSession);
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
