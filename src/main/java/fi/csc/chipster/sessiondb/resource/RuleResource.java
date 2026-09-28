package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

/**
 * Sharing rules of a session
 *
 * Only user and server tokens are allowed here. The scheduler creates session
 * tokens for jobs, and a job must not be able to share its session to other
 * users or remove the access of the users, whatever runs in the compute pod.
 * Dataset tokens are meant for reading one dataset, but they carry the username
 * of the user who created them, so without this restriction a leaked dataset
 * token could accept shares and delete the rules of that user, i.e. the whole
 * session.
 */
public class RuleResource {
	private UUID sessionId;
	private RuleTable ruleTable;
	private SessionDbApi sessionDbApi;
	private HibernateUtil hibernate;
	private int maxShareCount;

	public RuleResource(SessionResource sessionResource, UUID id, SessionDbApi sessionDbApi,
			RuleTable authorizationTable, Config config) {
		this.sessionId = id;
		this.ruleTable = authorizationTable;
		this.sessionDbApi = sessionDbApi;
		this.hibernate = sessionResource.getHibernate();
		this.maxShareCount = config.getInt(Config.KEY_SESSION_DB_MAX_SHARE_COUNT);

	}

	@GET
	@Path("{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // no session or dataset tokens, see the class comment
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response get(@PathParam("id") UUID authorizationId, @Context SecurityContext sc) throws IOException {

		ruleTable.checkSessionReadAuthorization(sc, sessionId);
		Rule result = getSessionRule(authorizationId);
		return Response.ok(result).build();
	}

	/**
	 * Get a rule of the session of this resource
	 *
	 * @param ruleId
	 * @return the rule, never null
	 * @throws NotFoundException if the rule doesn't exist or belongs to some other
	 *                           session
	 */
	private Rule getSessionRule(UUID ruleId) {
		Rule rule = ruleTable.getRule(sessionId, ruleId);
		if (rule == null) {
			throw new NotFoundException("rule not found");
		}
		return rule;
	}

	@GET
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // no session or dataset tokens, see the class comment
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getBySession(@Context SecurityContext sc) {
		ruleTable.checkSessionReadAuthorization(sc, sessionId);
		List<Rule> rules = this.sessionDbApi.getRules(sessionId);
		return Response.ok(rules).build();
	}

	@POST
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // no session or dataset tokens, see the class comment
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response post(Rule newRule, @Context UriInfo uriInfo, @Context SecurityContext sc) {

		if (newRule.getRuleId() != null) {
			throw new BadRequestException("authorization already has an id, post not allowed");
		}

		if (newRule.getUsername() == null ||
				newRule.getUsername().length() < 1 ||
				!(newRule.getUsername().equals(newRule.getUsername().trim()))) {
			throw new BadRequestException("invalid userId " + newRule.getUsername());
		}

		if (RuleTable.EVERYONE.equals(newRule.getUsername())) {
			if (!ruleTable.isAllowedToShareToEveryone(sc.getUserPrincipal().getName())) {
				throw new ForbiddenException("sharing to everyone is not allowed for this user");
			}
		}

		Session session = ruleTable.checkSessionReadWriteAuthorization(sc, sessionId);

		// don't allow client to set this
		newRule.setSharedBy(sc.getUserPrincipal().getName());

		// rules are created here only for shares, SessionResource calls the create()
		// method
		// directly when creating a rule for an own session
		if (this.maxShareCount >= 0) {
			if (this.ruleTable.getShares(sc.getUserPrincipal().getName()).size() > this.maxShareCount) {
				return Response.status(HttpStatus.SERVICE_UNAVAILABLE_503).entity("Too many shared sessions").build();
			}
		}

		UUID ruleId = sessionDbApi.createRule(newRule, session);

		sessionDbApi.sessionModified(session, hibernate.session());

		URI uri = uriInfo.getAbsolutePathBuilder().path(ruleId.toString()).build();

		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("ruleId", ruleId.toString());

		return Response.created(uri).entity(json).build();
	}

	@PUT
	@Path("{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // no session or dataset tokens, see the class comment
	@Consumes(MediaType.APPLICATION_JSON)
	@Transaction
	public Response put(Rule newRule, @PathParam("id") UUID ruleId, @Context UriInfo uriInfo,
			@Context SecurityContext sc) {

		Rule dbRule = getSessionRule(ruleId);

		// RolesAllowed annotation isn't anough for this
		String userId = sc.getUserPrincipal().getName();
		if (dbRule.getUsername() == null
				|| !(dbRule.getUsername().equals(userId) || Role.SESSION_WORKER.equals(userId))) {
			throw new ForbiddenException("wrong user");
		}

		if (newRule.getSharedBy() != null) {
			throw new BadRequestException("only sharedBy can be set to null");
		}

		hibernate.session().detach(dbRule);
		// the user is only allowed to set the sharedBy field to null to accept shares
		dbRule.setSharedBy(null);
		hibernate.update(dbRule, dbRule.getRuleId());

		List<Rule> sessionRules = ruleTable.getRules(sessionId);

		// pass the sharedBy username as extraRecipient to inform her about the
		// acceptance
		this.sessionDbApi.publishRuleEvent(sessionId, sessionRules, dbRule, EventType.UPDATE);

		return Response.noContent().build();
	}

	@DELETE
	@Path("{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // no session or dataset tokens, see the class comment
	@Transaction
	public Response delete(@PathParam("id") UUID ruleId, @Context SecurityContext sc) {

		Rule ruleToDelete = getSessionRule(ruleId);

		Session session = null;

		// everybody is allowed remove their own rules, even if they are read-only
		// avoid npe. It used to be possible to create a Rule with null username.  
		boolean isOwnRule = ruleToDelete.getUsername() != null && ruleToDelete.getUsername().equals(sc.getUserPrincipal().getName());
		// everybody is allowed remove rules shared by them
		boolean isSharedBy = ruleToDelete.getSharedBy() != null
				&& ruleToDelete.getSharedBy().equals(sc.getUserPrincipal().getName());

		if (!(isOwnRule || isSharedBy)) {
			// others need read-write permissions
			session = ruleTable.checkSessionReadWriteAuthorization(sc, sessionId);
		}

		this.sessionDbApi.deleteRule(ruleToDelete.getSession(), ruleToDelete, hibernate.session(), true);

		if (session != null) {
			sessionDbApi.sessionModified(session, hibernate.session());
		}

		return Response.noContent().build();
	}

}
