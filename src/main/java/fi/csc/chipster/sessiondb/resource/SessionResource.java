package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionState;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
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

@Path("sessions")
public class SessionResource {

	public static final String PATH_SHARES = "shares";
	private static final String QUERY_PARAM_PREVIEW = "preview";
	public static final String QUERY_PARAM_USER_ID = "userId";
	public static final String QUERY_PARAM_APP_ID = "appId";

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;

	private RuleTable ruleTable;
	private SessionDbApi sessionDbApi;

	private Config config;

	public SessionResource(HibernateUtil hibernate, SessionDbApi sessionDbApi, RuleTable authorizationTable,
			Config config) {
		this.hibernate = hibernate;
		this.ruleTable = authorizationTable;
		this.sessionDbApi = sessionDbApi;
		this.config = config;
	}

	// sub-resource locators
	@Path("{id}/datasets")
	public SessionDatasetResource getDatasetResource(@PathParam("id") UUID id) {
		return new SessionDatasetResource(this, id, sessionDbApi);
	}

	@Path("{id}/jobs")
	public SessionJobResource getJobResource(@PathParam("id") UUID id) {
		return new SessionJobResource(this, id, sessionDbApi);
	}

	@Path("{id}/rules")
	public RuleResource getRuleResource(@PathParam("id") UUID id) {
		return new RuleResource(this, id, this.sessionDbApi, this.ruleTable, config);
	}

	// CRUD
	@GET
	@Path("{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER, Role.SESSION_TOKEN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response get(@PathParam("id") UUID sessionId, @Context SecurityContext sc,
			@QueryParam(QUERY_PARAM_PREVIEW) String preview) throws IOException {

		// checks authorization
		Session dbSession = ruleTable.checkSessionReadAuthorization(sc, sessionId);

		if (dbSession == null) {
			throw new NotFoundException();
		}

		// FIXME what should initialize this?
		dbSession.getRules().size();

		// client can suggest not updating the access date by adding the query parameter
		// "preview"
		// empty string when set, otherwise null (boolean without value would have been
		// false)
		if (preview == null) {
			dbSession.setAccessed(Instant.now());
			getHibernate().update(dbSession, sessionId);
		}

		return Response.ok(dbSession).build();
	}

	@GET
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getAll(@QueryParam(QUERY_PARAM_USER_ID) String userIdString,
			@QueryParam(QUERY_PARAM_APP_ID) String appId, @Context SecurityContext sc) {

		String authenticatedUserId = sc.getUserPrincipal().getName();
		List<Session> sessions;

		if (Role.SESSION_WORKER.equals(authenticatedUserId) || Role.FILE_STORAGE.equals(authenticatedUserId)
				|| Role.FILE_BROKER.equals(authenticatedUserId)) {
			if (userIdString == null) {
				throw new ForbiddenException("query parameter " + QUERY_PARAM_USER_ID + " is null");
			}
			// session-worker needs access to support_session_owner's sessions
			// file-storage needs access to sessions to do the storage check
			// file-broker needs access to sessions to copy files between file-storages
			sessions = this.getSessions(ruleTable.getRulesOwn(userIdString));
		} else {
			if (userIdString != null) {
				throw new ForbiddenException(
						"query parameter " + QUERY_PARAM_USER_ID + " not allowed for the user " + authenticatedUserId);
			}

			if (appId != null) {
				// example sessions
				String exampleSessionOwner = this.ruleTable.getExampleSessionOwner(appId);

				sessions = this.getSessions(ruleTable.getRulesOfEveryone(exampleSessionOwner));

			} else {
				// user's own sessions
				sessions = this.getSessions(ruleTable.getRulesOwn(authenticatedUserId));
			}
		}

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(sessions)).build();
	}

	private List<Session> getSessions(List<Rule> rules) {

		List<Session> sessions = new ArrayList<>();
		for (Rule rule : rules) {
			Session session = rule.getSession();
			// user's own rule should be enough in the session list
			// otherwise we would be selecting rules of sessions of rules of username
			session.setRules(new HashSet<Rule>() {
				{
					add(rule);
				}
			});
			sessions.add(session);
		}

		return sessions;
	}

	@GET
	@Path(PATH_SHARES)
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getShares(@Context SecurityContext sc) {

		List<Rule> result = ruleTable.getShares(sc.getUserPrincipal().getName());

		List<Session> sessions = new ArrayList<>();
		for (Rule rule : result) {
			Session session = rule.getSession();
			// the shared rule should be enough in the session list
			// otherwise we would be selecting rules of sessions of rules of username
			session.setRules(new HashSet<Rule>() {
				{
					add(rule);
				}
			});
			sessions.add(session);
		}

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(sessions)).build();
	}

	@POST
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response post(Session session, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		// public Response post(String json, @Context UriInfo uriInfo, @Context
		// SecurityContext sc) {

		// curl -i -H "Content-Type: application/json" --user token:<TOKEN> -X POST
		// http://localhost:8080/sessionstorage/session

		/*
		 * Decide sessionId on the server
		 * 
		 * Sessions are more independent entities than datasets or jobs and there should
		 * be no need for the client to set it.
		 */
		if (session.getSessionId() != null) {
			throw new BadRequestException("session already has an id, post not allowed");
		}

		UUID id = RestUtils.createUUID();
		session.setSessionId(id);
		session.setCreated(Instant.now());
		session.setAccessed(Instant.now());

		String username = sc.getUserPrincipal().getName();
		if (username == null) {
			throw new NotAuthorizedException("username is null");
		}
		Rule auth = new Rule(username, true, null);
		auth.setRuleId(RestUtils.createUUID());
		auth.setSession(session);

		session.setRules(new LinkedHashSet<Rule>(Arrays.asList(new Rule[] { auth })));

		sessionDbApi.createSession(session, auth, getHibernate().session());

		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();

		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("sessionId", id.toString());

		return Response.created(uri).entity(json).build();
	}

	@PUT
	@Path("{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
	@Consumes(MediaType.APPLICATION_JSON)
	@Transaction
	public Response put(Session requestSession, @PathParam("id") UUID sessionId, @Context SecurityContext sc) {

		// override the url in json with the id in the url, in case a
		// malicious client has changed it
		requestSession.setSessionId(sessionId);

		// checks the authorization and verifies that the session exists
		Session dbSession = ruleTable.checkSessionReadWriteAuthorization(sc, sessionId);

		// get the state before this object is updated
		SessionState dbSessionState = dbSession.getState();

		requestSession.setAccessed(Instant.now());

		this.sessionDbApi.updateSession(requestSession, getHibernate().session());

		// allow client to change the state from IMPORT to TEMPORARY without changing it
		// directly to READY
		if (SessionState.IMPORT != dbSessionState) {
			sessionDbApi.sessionModified(requestSession, getHibernate().session());
		}

		return Response.noContent().build();
	}

	@DELETE
	@Path("{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
	@Transaction
	public Response delete(@PathParam("id") UUID id, @Context SecurityContext sc) {

		// check authorization
		Session session = ruleTable.checkSessionReadWriteAuthorization(sc, id);

		this.sessionDbApi.deleteSession(session, getHibernate().session());

		return Response.noContent().build();
	}
	
	@GET
	@Path("stats")
    @RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response getStats(@Context SecurityContext sc) {

        String authenticatedUserId = sc.getUserPrincipal().getName();
        
        HashMap<String, Object> responseObj = new HashMap<String, Object>() {{
            put("size", ruleTable.getTotalSize(authenticatedUserId));
        }};

        return Response.ok(responseObj).build();
    }


	/**
	 * Make a list compatible with JSON conversion
	 * 
	 * Default Java collections don't define the XmlRootElement annotation and thus
	 * can't be converted directly to JSON.
	 * 
	 * @param <T>
	 * 
	 * @param result
	 * @return
	 */
	private GenericEntity<List<Session>> toJaxbList(List<Session> result) {
		return new GenericEntity<List<Session>>(result) {
		};
	}

	public HibernateUtil getHibernate() {
		return hibernate;
	}

	public RuleTable getRuleTable() {
		return ruleTable;
	}

}
