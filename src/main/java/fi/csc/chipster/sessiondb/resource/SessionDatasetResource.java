package fi.csc.chipster.sessiondb.resource;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.security.RolesAllowed;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Root;
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
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.DatasetIdPair;
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
		
    	Dataset result = getDataset(sessionId, datasetId, getHibernate().session());
    	
    	if (result == null || !result.getSessionId().equals(sessionId)) {
    		throw new NotFoundException();
    	}

   		return Response.ok(result).build();
    }
    
    public static Dataset getDataset(UUID sessionId, UUID datasetId, org.hibernate.Session hibernateSession) {
    	Dataset dataset = hibernateSession.get(Dataset.class, new DatasetIdPair(sessionId, datasetId));
    	return dataset;
    }
    
	@GET
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAll(@Context SecurityContext sc) {

		Session session = sessionResource.getRuleTable().getSessionForReading(sc, sessionId);
		List<Dataset> result = getDatasets(getHibernate().session(), session);
		
		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(result)).build();
    }
	
	public static List<Dataset> getDatasets(org.hibernate.Session hibernateSession, Session session) {
		
		CriteriaBuilder cb = hibernateSession.getCriteriaBuilder();
		CriteriaQuery<Dataset> c = cb.createQuery(Dataset.class);
		Root<Dataset> r = c.from(Dataset.class);
		r.fetch("file", JoinType.LEFT);
		c.select(r);		
		c.where(cb.equal(r.get("datasetIdPair").get("sessionId"), session.getSessionId()));		
		List<Dataset> datasets = HibernateUtil.getEntityManager(hibernateSession).createQuery(c).getResultList();	
				
		return datasets;
	}
	
	@POST
	@Path(RestUtils.PATH_ARRAY)
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response post(Dataset[] datasets, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		List<UUID> ids = postList(Arrays.asList(datasets), uriInfo, sc);
		
		ObjectNode json = RestUtils.getArrayResponse("datasets", "datasetId", ids);		
		
		return Response.created(uriInfo.getRequestUri()).entity(json).build();
	}

	@POST
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response post(Dataset dataset, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		List<UUID> ids = this.postList(Arrays.asList(new Dataset[] {dataset}), uriInfo, sc);
		UUID id = ids.get(0);
		
		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();
		
		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("datasetId", id.toString());
		
		return Response.created(uri).entity(json).build();
	}
    	        					
	public List<UUID> postList(List<Dataset> datasets, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		for (Dataset dataset : datasets) {
			// client's are allowed to set the datasetId to preserve the object references within the session
			UUID datasetId = dataset.getDatasetId();
			if (datasetId == null) {
				datasetId = RestUtils.createUUID();
			}
			
			// make sure a hostile client doesn't set the session
			if (dataset.getSessionId() != null && !sessionId.equals(dataset.getSessionId())) {
				throw new BadRequestException("different sessionId in the dataset object and in the url");
			}
			dataset.setDatasetIdPair(sessionId, datasetId);
		}
		
		// check authorization
		sessionResource.getRuleTable().getSessionForWriting(sc, sessionId);
		
		for (Dataset dataset : datasets) {			
			if (dataset.getCreated() == null) {
				dataset.setCreated(Instant.now());
			}
			
			create(dataset, getHibernate().session());		
		}
		
		return datasets.stream()
				.map(d -> d.getDatasetId())
				.collect(Collectors.toList());
    }
	
	public void create(Dataset dataset, org.hibernate.Session hibernateSession) {
		
		checkFileModification(dataset, hibernateSession);
		
		if (dataset.getFile() != null) {
			// why CascadeType.PERSIST isn't enough?
			HibernateUtil.persist(dataset.getFile(), hibernateSession);
		}
		HibernateUtil.persist(dataset, hibernateSession);
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
	@Path(RestUtils.PATH_ARRAY)	
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response putArray(Dataset[] requestDatasets, @PathParam("id") UUID datasetId, @Context SecurityContext sc) {
		this.putList(Arrays.asList(requestDatasets), sc);
		return Response.noContent().build();
	}
	
	@PUT
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
	@Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response put(Dataset requestDataset, @PathParam("id") UUID datasetId, @Context SecurityContext sc) {
		
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		requestDataset.setDatasetIdPair(sessionId, datasetId);
		
		this.putList(Arrays.asList(new Dataset[] {requestDataset}), sc);
		
		return Response.noContent().build();
	}
		
    public void putList(List<Dataset> requestDatasets, @Context SecurityContext sc) {				    	
		
		/*
		 * Checks that
		 * - user has write authorization for the session
		 * - the session contains this dataset
		 */
		Session session = sessionResource.getRuleTable().getSessionForWriting(sc, sessionId);
		
		HashMap<UUID, Dataset> dbDatasets = new HashMap<>();
		
		for (Dataset requestDataset : requestDatasets) {
			UUID datasetId = requestDataset.getDatasetId();
			Dataset dbDataset = getDataset(sessionId, datasetId, getHibernate().session());
			
			if (dbDataset == null || !dbDataset.getSessionId().equals(session.getSessionId())) {
				throw new NotFoundException("dataset doesn't exist");
			}
			
			dbDatasets.put(datasetId, dbDataset);
		}
		
		for (Dataset requestDataset : requestDatasets) {
			if (!sc.isUserInRole(Role.FILE_BROKER)) {
				checkFileModification(requestDataset, getHibernate().session());
			}
			
			if (requestDataset.getFile() == null || requestDataset.getFile().isEmpty()) {
				// if the client doesn't care about the File, simply keep the db version
				requestDataset.setFile(dbDatasets.get(requestDataset.getDatasetId()).getFile());
			}
			
			// make sure a hostile client doesn't set the session
			requestDataset.setDatasetIdPair(session.getSessionId(), requestDataset.getDatasetId());
		}
		
		for (Dataset requestDataset : requestDatasets) {
			update(requestDataset, dbDatasets.get(requestDataset.getDatasetId()), getHibernate().session());
		}
    }
	
	public void update(Dataset newDataset, Dataset dbDataset, org.hibernate.Session hibernateSession) {
		
		if (newDataset.getFile() != null) {
			if (dbDataset.getFile() == null) {
				HibernateUtil.persist(newDataset.getFile(), hibernateSession);
			} else {
				HibernateUtil.update(newDataset.getFile(), newDataset.getFile().getFileId(), hibernateSession);
			}
		}
		
		HibernateUtil.update(newDataset, newDataset.getDatasetIdPair(), hibernateSession);
		sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.DATASET, newDataset.getDatasetId(), EventType.UPDATE), hibernateSession);
	}

	@DELETE
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
    @Path("{id}")
	@Transaction
    public Response delete(@PathParam("id") UUID datasetId, @Context SecurityContext sc) {

		// checks authorization
		Session session = sessionResource.getRuleTable().getSessionForWriting(sc, sessionId);
		Dataset dataset = getDataset(sessionId, datasetId, sessionResource.getHibernate().session());
		
		if (dataset == null || !dataset.getSessionId().equals(session.getSessionId())) {
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

		HibernateUtil.delete(dataset, dataset.getDatasetIdPair(), getHibernate().session());
		
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
				// remove from file-broker
				sessionResource.publish(SessionDbTopicConfig.FILES_TOPIC, new SessionEvent(sessionId, ResourceType.FILE, fileId, EventType.DELETE), hibernateSession);
				// remove from db
				HibernateUtil.delete(dataset.getFile(), dataset.getFile().getFileId(), hibernateSession);
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
