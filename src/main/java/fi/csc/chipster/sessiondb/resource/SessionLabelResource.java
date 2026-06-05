package fi.csc.chipster.sessiondb.resource;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Label;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

public class SessionLabelResource {

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	final private UUID sessionId;

	private SessionResource sessionResource;
	private SessionDbApi sessionDbApi;

	public SessionLabelResource() {
		sessionId = null;
	}

	public SessionLabelResource(SessionResource sessionResource, UUID id, SessionDbApi sessionDbApi) {
		this.sessionResource = sessionResource;
		this.sessionId = id;
		this.sessionDbApi = sessionDbApi;
	}

	@GET
	@Path("{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER, Role.SESSION_TOKEN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response get(@PathParam("id") UUID labelId, @Context SecurityContext sc) {

		sessionResource.getRuleTable().checkSessionReadAuthorization(sc, sessionId);

		Label result = SessionDbApi.getLabel(sessionId, labelId, getHibernate().session());

		if (result == null || !result.getSessionId().equals(sessionId)) {
			throw new NotFoundException();
		}

		return Response.ok(result).build();
	}

	@GET
	@RolesAllowed({ Role.CLIENT, Role.SERVER, Role.SESSION_TOKEN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getAll(@Context SecurityContext sc) {

		sessionResource.getRuleTable().checkSessionReadAuthorization(sc, sessionId);

		List<Label> result = SessionDbApi.getLabels(getHibernate().session(), sessionId);

		return Response.ok(toJaxbList(result)).build();
	}

	@POST
	@RolesAllowed({ Role.CLIENT, Role.SERVER })
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response post(Label label, @Context UriInfo uriInfo, @Context SecurityContext sc) {

		Session session = sessionResource.getRuleTable().checkSessionReadWriteAuthorization(sc, sessionId);

		validateName(label);

		int existingLabelCount = SessionDbApi.getLabels(getHibernate().session(), sessionId).size();
		if (existingLabelCount >= Label.MAX_LABELS_PER_SESSION) {
			throw new BadRequestException(
					"session has reached the maximum of " + Label.MAX_LABELS_PER_SESSION + " labels");
		}

		UUID labelId = label.getLabelId();
		if (labelId == null) {
			labelId = RestUtils.createUUID();
		}

		if (label.getSessionId() != null && !sessionId.equals(label.getSessionId())) {
			throw new BadRequestException("different sessionId in the label object and in the url");
		}
		label.setLabelIdPair(sessionId, labelId);

		if (label.getCreated() == null) {
			label.setCreated(Instant.now());
		}

		sessionDbApi.createLabel(label, sessionId, getHibernate().session());
		sessionDbApi.sessionModified(session, getHibernate().session());

		URI uri = uriInfo.getAbsolutePathBuilder().path(labelId.toString()).build();
		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("labelId", labelId.toString());

		return Response.created(uri).entity(json).build();
	}

	@PUT
	@RolesAllowed({ Role.CLIENT, Role.SERVER })
	@Path("{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Transaction
	public Response put(Label requestLabel, @PathParam("id") UUID labelId, @Context SecurityContext sc) {

		Session session = sessionResource.getRuleTable().checkSessionReadWriteAuthorization(sc, sessionId);

		validateName(requestLabel);

		Label dbLabel = SessionDbApi.getLabel(sessionId, labelId, getHibernate().session());
		if (dbLabel == null || !dbLabel.getSessionId().equals(session.getSessionId())) {
			throw new NotFoundException("label doesn't exist");
		}

		// override the url in json with the id in the url
		requestLabel.setLabelIdPair(sessionId, labelId);
		// preserve created from the db; clients can't change it
		requestLabel.setCreated(dbLabel.getCreated());

		sessionDbApi.updateLabel(requestLabel, sessionId, getHibernate().session());
		sessionDbApi.sessionModified(session, getHibernate().session());

		return Response.noContent().build();
	}

	@DELETE
	@RolesAllowed({ Role.CLIENT, Role.SERVER })
	@Path("{id}")
	@Transaction
	public Response delete(@PathParam("id") UUID labelId, @Context SecurityContext sc) {

		Session session = sessionResource.getRuleTable().checkSessionReadWriteAuthorization(sc, sessionId);

		Label label = SessionDbApi.getLabel(sessionId, labelId, getHibernate().session());
		if (label == null || !label.getSessionId().equals(session.getSessionId())) {
			throw new NotFoundException("label not found");
		}

		sessionDbApi.deleteLabel(label, sessionId, session, getHibernate().session());
		sessionDbApi.sessionModified(session, getHibernate().session());

		return Response.noContent().build();
	}

	private static void validateName(Label label) {
		String name = label.getName();
		if (name == null || name.trim().isEmpty()) {
			throw new BadRequestException("label name is required");
		}
		if (name.length() > Label.MAX_NAME_LENGTH) {
			throw new BadRequestException("label name longer than " + Label.MAX_NAME_LENGTH + " characters");
		}
	}

	public static GenericEntity<Collection<Label>> toJaxbList(Collection<Label> result) {
		return new GenericEntity<Collection<Label>>(result) {
		};
	}

	private HibernateUtil getHibernate() {
		return sessionResource.getHibernate();
	}
}
