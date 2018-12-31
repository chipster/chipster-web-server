package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;

public class RuleResource {
	private UUID sessionId;
	private RuleTable ruleTable;
	private HibernateUtil hibernate;
	private SessionResource sessionResource;
	private int maxShareCount;
	private String restrictSharingToEveryone;

	public RuleResource(SessionResource sessionResource, UUID id, RuleTable authorizationTable, Config config) {
		this.sessionId = id;
		this.ruleTable = authorizationTable;
		this.sessionResource = sessionResource;
		this.hibernate = sessionResource.getHibernate();
		this.maxShareCount = config.getInt(Config.KEY_SESSION_DB_MAX_SHARE_COUNT);
		this.restrictSharingToEveryone = config.getString(Config.KEY_SESSION_DB_RESTRICT_SHARING_TO_EVERYONE);
	}

	@GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response get(@PathParam("id") UUID authorizationId, @Context SecurityContext sc) throws IOException {
    	
		ruleTable.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, false);
		Rule result = ruleTable.getRule(authorizationId, hibernate.session());
    	if (result == null) {
    		throw new NotFoundException();
    	}	
    	return Response.ok(result).build();    	
    }

    
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response getBySession(@Context SecurityContext sc) {
    	    	
		ruleTable.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, false);    
    	List<Rule> rules = getRules(sessionId);    	
    	return Response.ok(rules).build();	       
    }
    
    public List<Rule> getRules(UUID sessionId) {
    	return ruleTable.getRules(sessionId);
    }
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response post(Rule newRule, @Context UriInfo uriInfo, @Context SecurityContext sc) {
    	
		if (newRule.getRuleId() != null) {
			throw new BadRequestException("authorization already has an id, post not allowed");
		}
		
		if (RuleTable.EVERYONE.equals(newRule.getUsername())) {
			if (!restrictSharingToEveryone.isEmpty() && !restrictSharingToEveryone.equals(sc.getUserPrincipal().getName())) {
				throw new ForbiddenException("sharing to everyone is not allowed for this user");
			}
		}
    	
		Session session = ruleTable.getSessionForWriting(sc, sessionId);

		// don't allow client to set this
		newRule.setSharedBy(sc.getUserPrincipal().getName());
		
		// rules are created here only for shares, SessionResource calls the create() method
		// directly when creating a rule for an own session
    	if (this.maxShareCount >= 0) {
    		if (this.ruleTable.getShares(sc.getUserPrincipal().getName()).size() > this.maxShareCount) {
    			return Response.status(HttpStatus.SERVICE_UNAVAILABLE_503).entity("Too many shared sessions").build();
    		}
    	}

		UUID ruleId = this.create(newRule, session);
		
		sessionResource.sessionModified(session, hibernate.session());
    	
    	URI uri = uriInfo.getAbsolutePathBuilder().path(ruleId.toString()).build();
    	
    	ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("ruleId", ruleId.toString());
		
		return Response.created(uri).entity(json).build();
    }
    
    public UUID create(Rule newRule, Session session) {
    	
		newRule.setRuleId(RestUtils.createUUID());    	    	
		
		// make sure a hostile client doesn't set the session
    	newRule.setSession(session);
    	
    	newRule.setCreated(Instant.now());
    	
    	ruleTable.save(newRule, hibernate.session());    	
    	
    	publishRuleEvent(session.getSessionId(), session.getRules(), newRule, EventType.CREATE);
    	
    	return newRule.getRuleId();
    }
    
    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transaction
    public Response put(Rule newRule,  @PathParam("id") UUID ruleId, @Context UriInfo uriInfo, @Context SecurityContext sc) {    			    	
    	
    	List<Rule> sessionRules = ruleTable.getRules(sessionId);
    	
    	Optional<Rule> dbRuleOptional = sessionRules.stream().filter(r -> ruleId.equals(r.getRuleId())).findAny();
    	if (!dbRuleOptional.isPresent()) {
    		throw new NotFoundException("rule not found");
    	}
    	
    	Rule dbRule = dbRuleOptional.get();
    	
    	// RolesAllowed annotation isn't anough for this
    	String userId = sc.getUserPrincipal().getName();
    	if (dbRule.getUsername() == null || !( 
    			dbRule.getUsername().equals(userId)
				|| Role.SESSION_WORKER.equals(userId))) {
    		throw new ForbiddenException("wrong user");
    	}
		
    	if (newRule.getSharedBy() != null) {
    		throw new BadRequestException("only sharedBy can be set to null");
    	}
    		
		hibernate.session().detach(dbRule);
		// the user is only allowed to set the sharedBy field to null to accept shares
		dbRule.setSharedBy(null);
		hibernate.update(dbRule, dbRule.getRuleId());
		
		// pass the sharedBy username as extraRecipient to inform her about the acceptance 
		publishRuleEvent(sessionId, sessionRules, dbRule, EventType.UPDATE);
		
		return Response.noContent().build();
    }
    
    private void publishRuleEvent(UUID sessionId, Collection<Rule> sessionRules, Rule rule, EventType eventType) {    	

    	sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.RULE, rule.getRuleId(), eventType), hibernate.session());
    	    	
    	Set<String> usernames = sessionRules.stream()
        		// don't inform about the example sessions
    			.filter(r -> !RuleTable.EVERYONE.equals(r.getUsername()))
    			.map(r -> r.getUsername())
    			.collect(Collectors.toSet());
    	
    	// when session is being shared, the recipient is not in the session yet
    	usernames.add(rule.getUsername());
    	
    	// send events to username topics to update the session list
    	for (String username : usernames) {
			sessionResource.publish(username, new SessionEvent(sessionId, ResourceType.RULE, rule.getRuleId(), eventType), hibernate.session());
    	}
	}

	@DELETE
    @Path("{id}")
    @Transaction
    public Response delete(@PathParam("id") UUID ruleId, @Context SecurityContext sc) {
    	
    	Rule ruleToDelete = ruleTable.getRule(ruleId, hibernate.session());
    	
    	if (ruleToDelete == null) {
    		throw new NotFoundException("rule not found");
    	}
    	
    	Session session = null;
    	
    	// everybody is allowed remove their own rules, even if they are read-only
    	boolean isOwnRule = ruleToDelete.getUsername().equals(sc.getUserPrincipal().getName());
    	// everybody is allowed remove rules shared by them
    	boolean isSharedBy = ruleToDelete.getSharedBy() != null && ruleToDelete.getSharedBy().equals(sc.getUserPrincipal().getName());
    	
    	if (!(isOwnRule || isSharedBy)) {
    		// others need read-write permissions
    		session = ruleTable.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, true);
    	}    	    	
 
    	delete(ruleToDelete.getSession(), ruleToDelete, hibernate.session(), true);
    	
    	if (session != null) {
    		sessionResource.sessionModified(session, hibernate.session());
    	}
    
    	return Response.noContent().build();
    }

	public void delete(Session session, Rule rule, org.hibernate.Session hibernateSession, boolean deleteSessionIfLastRule) {
		
		ruleTable.delete(session.getSessionId(), rule, hibernate.session());		
		
		// why session.getRules() complains: failed to lazily initialize a collection, could not initialize proxy - no Session?
		List<Rule> sessionRules = ruleTable.getRules(session.getSessionId());
		
		publishRuleEvent(session.getSessionId(), sessionRules, rule, EventType.DELETE);
		
		if (deleteSessionIfLastRule) {
			sessionResource.deleteSessionIfOrphan(session);
		}

	}   
}
