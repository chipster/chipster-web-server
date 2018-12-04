package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.List;
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
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
	private Config config;
	private SessionResource sessionResource;

	public RuleResource(SessionResource sessionResource, UUID id, RuleTable authorizationTable, Config config) {
		this.sessionId = id;
		this.ruleTable = authorizationTable;
		this.sessionResource = sessionResource;
		this.hibernate = sessionResource.getHibernate();
		this.config = config;
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
			String publicSharingUsername = config.getString(Config.KEY_SESSION_DB_RESTRICT_SHARING_TO_EVERYONE);
			if (!publicSharingUsername.isEmpty() && !publicSharingUsername.equals(sc.getUserPrincipal().getName())) {
				throw new ForbiddenException("sharing to everyone is not allowed for this user");
			}
		}
    	
		Session session = ruleTable.getSessionForWriting(sc, sessionId);

		// don't allow client to set this
		newRule.setSharedBy(sc.getUserPrincipal().getName());

		UUID ruleId = this.create(newRule, session);
    	
    	URI uri = uriInfo.getAbsolutePathBuilder().path(ruleId.toString()).build();
    	
    	ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("ruleId", ruleId.toString());
		
		return Response.created(uri).entity(json).build();
    }
    
    public UUID create(Rule newRule, Session session) {
    	
		newRule.setRuleId(RestUtils.createUUID());    	    	
		
		// make sure a hostile client doesn't set the session
    	newRule.setSession(session);    	
    	
    	ruleTable.save(newRule, hibernate.session());    	
    	
    	publishRuleEvent(session.getSessionId(), session.getRules(), newRule, EventType.CREATE);
    	
    	return newRule.getRuleId();
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
    public Response delete(@PathParam("id") UUID authorizationId, @Context SecurityContext sc) {
    	
    	Rule ruleToDelete = ruleTable.getRule(authorizationId, hibernate.session());
    	
    	if (ruleToDelete == null) {
    		throw new NotFoundException("rule not found");
    	}
    	
    	// everybody is allowed remove their own rules, even if they are read-only
    	if (!ruleToDelete.getUsername().equals(sc.getUserPrincipal().getName())) {
    		// others need read-write permissions
    		ruleTable.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, true);
    	}    	    	
 
    	delete(ruleToDelete.getSession(), ruleToDelete, hibernate.session(), true);
    
    	return Response.noContent().build();
    }

	public void delete(Session session, Rule rule, org.hibernate.Session hibernateSession, boolean deleteSessionIfLastRule) {
		
		ruleTable.delete(session.getSessionId(), rule, hibernate.session());		
		
		if (deleteSessionIfLastRule) {
			sessionResource.deleteSessionIfOrphan(session);
		}
		
		// why session.getRules() complains: failed to lazily initialize a collection, could not initialize proxy - no Session?
		List<Rule> sessionRules = ruleTable.getRules(session.getSessionId());
		
		publishRuleEvent(session.getSessionId(), sessionRules, rule, EventType.DELETE);
	}   
}
