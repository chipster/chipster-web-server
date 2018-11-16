package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

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
    public Response post(Rule newAuthorization, @Context UriInfo uriInfo, @Context SecurityContext sc) {
    	
		if (newAuthorization.getRuleId() != null) {
			throw new BadRequestException("authorization already has an id, post not allowed");
		}
		
		if (RuleTable.EVERYONE.equals(newAuthorization.getUsername())) {
			String publicSharingUsername = config.getString(Config.KEY_SESSION_DB_RESTRICT_SHARING_TO_EVERYONE);
			if (!publicSharingUsername.isEmpty() && !publicSharingUsername.equals(sc.getUserPrincipal().getName())) {
				throw new ForbiddenException("sharing to everyone is not allowed for this user");
			}
		}
    	
		Session session = ruleTable.getSessionForWriting(sc, sessionId);

		newAuthorization.setRuleId(RestUtils.createUUID());    	    	
		
		// make sure a hostile client doesn't set the session
    	newAuthorization.setSession(session);
    	newAuthorization.setSharedBy(sc.getUserPrincipal().getName());
    	
    	ruleTable.save(newAuthorization, hibernate.session());
    
    	sessionResource.publish(sessionId.toString(), new SessionEvent(sessionId, ResourceType.RULE, newAuthorization.getRuleId(), EventType.CREATE), hibernate.session());
    	
    	URI uri = uriInfo.getAbsolutePathBuilder().path(newAuthorization.getRuleId().toString()).build();
    	
    	ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("ruleId", newAuthorization.getRuleId().toString());
		
		return Response.created(uri).entity(json).build();
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
		
		sessionResource.publish(session.getSessionId().toString(), new SessionEvent(session.getSessionId(), ResourceType.RULE, rule.getRuleId(), EventType.DELETE), hibernateSession);
		
		if (deleteSessionIfLastRule) {
			sessionResource.deleteSessionIfOrphan(session);
		}
	}   
}
