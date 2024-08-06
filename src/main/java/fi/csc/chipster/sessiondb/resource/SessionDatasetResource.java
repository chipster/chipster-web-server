package fi.csc.chipster.sessiondb.resource;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.FileUtils;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Session;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

public class SessionDatasetResource {

	public static final String QUERY_PARAM_READ_WRITE = "read-write";

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	final private UUID sessionId;

	private SessionResource sessionResource;
	private SessionDbApi sessionDbApi;

	public SessionDatasetResource() {
		sessionId = null;
	}

	public SessionDatasetResource(SessionResource sessionResource, UUID id, SessionDbApi sessionDbApi) {
		this.sessionResource = sessionResource;
		this.sessionId = id;
		this.sessionDbApi = sessionDbApi;
	}

	// CRUD
	@GET
	@Path("{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER, Role.SESSION_TOKEN, Role.DATASET_TOKEN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response get(@PathParam("id") UUID datasetId, @QueryParam(QUERY_PARAM_READ_WRITE) boolean requireReadWrite,
			@Context SecurityContext sc) {

		// checks authorization
		/*
		 * Client can require write permissions if it wants to make sure a modification
		 * is allowed later. This assumes that there are no Rules that would allow only
		 * write but not read access.
		 */
		sessionResource.getRuleTable().checkDatasetAuthorization(sc, sessionId, datasetId, requireReadWrite);

		Dataset result = SessionDbApi.getDataset(sessionId, datasetId, getHibernate().session());

		if (result == null || !result.getSessionId().equals(sessionId)) {
			throw new NotFoundException();
		}

		return Response.ok(result).build();
	}

	@GET
	@RolesAllowed({ Role.CLIENT, Role.SERVER, Role.SESSION_TOKEN, Role.DATASET_TOKEN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getAll(@Context SecurityContext sc) {

		// checks authorization
		Session session = sessionResource.getRuleTable().checkSessionReadAuthorization(sc, sessionId);

		List<Dataset> result = SessionDbApi.getDatasets(getHibernate().session(), session);

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(result)).build();
	}

	@POST
	@Path(RestUtils.PATH_ARRAY)
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response post(Dataset[] datasets, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		List<UUID> ids = postList(Arrays.asList(datasets), uriInfo, sc);

		ObjectNode json = RestUtils.getArrayResponse("datasets", "datasetId", ids);

		return Response.created(uriInfo.getRequestUri()).entity(json).build();
	}

	@POST
	@RolesAllowed({ Role.CLIENT, Role.SERVER, Role.SESSION_TOKEN }) // don't allow Role.UNAUTHENTICATED
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response post(Dataset dataset, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		List<UUID> ids = this.postList(Arrays.asList(new Dataset[] { dataset }), uriInfo, sc);
		UUID id = ids.get(0);

		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();

		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("datasetId", id.toString());

		return Response.created(uri).entity(json).build();
	}

	private List<UUID> postList(List<Dataset> datasets, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		for (Dataset dataset : datasets) {
			// client's are allowed to set the datasetId to preserve the object references
			// within the session
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
		Session session = sessionResource.getRuleTable().checkSessionReadWriteAuthorization(sc, sessionId);

		for (Dataset dataset : datasets) {
			if (dataset.getCreated() == null) {
				dataset.setCreated(Instant.now());
			}

			sessionDbApi.createDataset(dataset, sessionId, getHibernate().session());
		}

		sessionDbApi.sessionModified(session, getHibernate().session());

		return datasets.stream().map(d -> d.getDatasetId()).collect(Collectors.toList());
	}

	@PUT
	@Path(RestUtils.PATH_ARRAY)
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
	@Consumes(MediaType.APPLICATION_JSON)
	@Transaction
	public Response putArray(Dataset[] requestDatasets, @PathParam("id") UUID datasetId, @Context SecurityContext sc) {
		this.putList(Arrays.asList(requestDatasets), sc);
		return Response.noContent().build();
	}

	@PUT
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
	@Path("{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Transaction
	public Response put(Dataset requestDataset, @PathParam("id") UUID datasetId, @Context SecurityContext sc) {

		// override the url in json with the id in the url, in case a
		// malicious client has changed it
		requestDataset.setDatasetIdPair(sessionId, datasetId);

		this.putList(Arrays.asList(new Dataset[] { requestDataset }), sc);

		return Response.noContent().build();
	}

	private void putList(List<Dataset> requestDatasets, @Context SecurityContext sc) {

		/*
		 * Checks that - user has write authorization for the session - the session
		 * contains this dataset
		 */
		Session session = sessionResource.getRuleTable().checkSessionReadWriteAuthorization(sc, sessionId);

		HashMap<UUID, Dataset> dbDatasets = new HashMap<>();

		for (Dataset requestDataset : requestDatasets) {
			UUID datasetId = requestDataset.getDatasetId();
			Dataset dbDataset = SessionDbApi.getDataset(sessionId, datasetId, getHibernate().session());

			if (dbDataset == null || !dbDataset.getSessionId().equals(session.getSessionId())) {
				throw new NotFoundException("dataset doesn't exist");
			}

			dbDatasets.put(datasetId, dbDataset);
		}

		for (Dataset requestDataset : requestDatasets) {
			if (!sc.isUserInRole(Role.FILE_BROKER)) {
				sessionDbApi.checkFileModification(requestDataset, getHibernate().session());

				// file-broker needs to set File to null when S3 upload is cancelled
				if (FileUtils.isEmpty(requestDataset.getFile())) {
					// if the client doesn't care about the File, simply keep the db version
					requestDataset.setFile(dbDatasets.get(requestDataset.getDatasetId()).getFile());
				}
			}

			// make sure a hostile client doesn't set the session
			requestDataset.setDatasetIdPair(session.getSessionId(), requestDataset.getDatasetId());

			// the created timestamp needs to be overridden with a value from the client
			// when openig a zip session
		}

		for (Dataset requestDataset : requestDatasets) {
			this.sessionDbApi.updateDataset(requestDataset, dbDatasets.get(requestDataset.getDatasetId()), sessionId,
					getHibernate().session());
		}

		sessionDbApi.sessionModified(session, getHibernate().session());
	}

	/**
	 * Delete dataset
	 * 
	 * Deletion with session token is needed in comp when it retries failed uploads.
	 * 
	 * @param datasetId
	 * @param sc
	 * @return
	 */
	@DELETE
	@RolesAllowed({ Role.CLIENT, Role.SERVER, Role.SESSION_TOKEN }) // don't allow Role.UNAUTHENTICATED
	@Path("{id}")
	@Transaction
	public Response delete(@PathParam("id") UUID datasetId, @Context SecurityContext sc) {

		// checks authorization
		Session session = sessionResource.getRuleTable().checkSessionReadWriteAuthorization(sc, sessionId);
		Dataset dataset = SessionDbApi.getDataset(sessionId, datasetId, sessionResource.getHibernate().session());

		if (dataset == null || !dataset.getSessionId().equals(session.getSessionId())) {
			throw new NotFoundException("dataset not found");
		}

		this.sessionDbApi.deleteDataset(dataset, sessionId);

		sessionDbApi.sessionModified(session, getHibernate().session());

		return Response.noContent().build();
	}

	/**
	 * Make a collection compatible with JSON conversion
	 * 
	 * Default Java collections don't define the XmlRootElement annotation and thus
	 * can't be converted directly to JSON.
	 * 
	 * @param <T>
	 * 
	 * @param result
	 * @return
	 */
	public static GenericEntity<Collection<Dataset>> toJaxbList(Collection<Dataset> result) {
		return new GenericEntity<Collection<Dataset>>(result) {
		};
	}

	private HibernateUtil getHibernate() {
		return sessionResource.getHibernate();
	}
}
