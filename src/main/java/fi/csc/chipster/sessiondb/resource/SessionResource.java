package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import org.hibernate.BaseSessionEventListener;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.sessiondb.model.SessionState;

@Path("sessions")
public class SessionResource {
	
	public static final String PATH_SHARES = "shares";
	private static final String QUERY_PARAM_PREVIEW = "preview";
	public static final String QUERY_PARAM_USER_ID = "userId";
	public static final String QUERY_PARAM_APP_ID = "appId";

	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;
	private PubSubServer events;

	private RuleTable ruleTable;

	private Config config;
	
	public SessionResource(HibernateUtil hibernate, RuleTable authorizationTable, Config config) {
		this.hibernate = hibernate;
		this.ruleTable = authorizationTable;
		this.config = config;
	}

	// sub-resource locators
	@Path("{id}/datasets")
	public SessionDatasetResource getDatasetResource(@PathParam("id") UUID id) {
		return new SessionDatasetResource(this, id);
	}
	
	@Path("{id}/jobs")
	public SessionJobResource getJobResource(@PathParam("id") UUID id) {
		return new SessionJobResource(this, id);
	}
	
	@Path("{id}/rules")
	public RuleResource getRuleResource(@PathParam("id") UUID id) {
		return new RuleResource(this, id, this.ruleTable, config);
	}
	
    // CRUD
    @GET
    @Path("{id}")
    @RolesAllowed({ Role.CLIENT, Role.SERVER, Role.SESSION_DB_TOKEN})
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response get(@PathParam("id") UUID sessionId, @Context SecurityContext sc, @QueryParam(QUERY_PARAM_PREVIEW) String preview) throws IOException {    
    	    
		// checks authorization
		Session dbSession = ruleTable.checkAuthorizationForSessionRead(sc, sessionId);
		
    	
    	if (dbSession == null) {
    		throw new NotFoundException();
    	}
    	
    	//FIXME what should initialize this?
    	dbSession.getRules().size();
    	
    	// client can suggest not updating the access date by adding the query parameter "preview"
    	// empty string when set, otherwise null (boolean without value would have been false)
    	if (preview == null) {
	    	dbSession.setAccessed(Instant.now());
	    	getHibernate().update(dbSession, sessionId);
    	}
    	
    	return Response.ok(dbSession).build();    	
    }
    
	@GET
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
    @Produces(MediaType.APPLICATION_JSON)	
	@Transaction
    public Response getAll(@QueryParam(QUERY_PARAM_USER_ID) String userIdString, @QueryParam(QUERY_PARAM_APP_ID) String appId, @Context SecurityContext sc) {

		String authenticatedUserId = sc.getUserPrincipal().getName();
		List<Session> sessions;
		
		if (Role.SESSION_WORKER.equals(authenticatedUserId) || Role.FILE_STORAGE.equals(authenticatedUserId)) {
			if (userIdString == null) {
				throw new ForbiddenException("query parameter " + QUERY_PARAM_USER_ID + " is null");
			}
			// session-worker needs access to support_session_owner's sessions
			// file-storage needs access to sessions to do the storage check
			sessions = this.getSessions(ruleTable.getRulesOwn(userIdString));
		} else {
			if (userIdString != null) {
				throw new ForbiddenException("query parameter " + QUERY_PARAM_USER_ID + " not allowed for the user " + authenticatedUserId);
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
			// otherwise we would be selectin rules of sessions of rules of username
			session.setRules(new HashSet<Rule>() {{ add(rule); }});
			sessions.add(session);
		}
		
		return sessions;
	}

	@GET
	@Path(PATH_SHARES)
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
    @Produces(MediaType.APPLICATION_JSON)	
	@Transaction
    public Response getShares(@Context SecurityContext sc) {

		List<Rule> result = ruleTable.getShares(sc.getUserPrincipal().getName());
		
		List<Session> sessions = new ArrayList<>();
		for (Rule rule : result) {
			Session session = rule.getSession();
			// the shared rule should be enough in the session list
			// otherwise we would be selecting rules of sessions of rules of username
			session.setRules(new HashSet<Rule>() {{ add(rule); }});
			sessions.add(session);
		}

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(sessions)).build();		
    }

	@POST
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response post(Session session, @Context UriInfo uriInfo, @Context SecurityContext sc) {
	//public Response post(String json, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		
		// curl -i -H "Content-Type: application/json" --user token:<TOKEN> -X POST http://localhost:8080/sessionstorage/session
		
		/*
		 * Decide sessionId on the server
		 * 
		 * Sessions are more independent entities than datasets or jobs and there
		 * should be no need for the client to set it.
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
		
		session.setRules(new LinkedHashSet<Rule>(Arrays.asList(new Rule[] {auth})));
		
		create(session, auth, getHibernate().session());
		
		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();
		
		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("sessionId", id.toString());
		
		return Response.created(uri).entity(json).build();
    }
	
	public void create(Session session, Rule auth, org.hibernate.Session hibernateSession) {
		HibernateUtil.persist(session, hibernateSession);
		
		getRuleResource(session.getSessionId()).create(auth, session);

		UUID sessionId = session.getSessionId();
		SessionEvent event = new SessionEvent(sessionId, ResourceType.SESSION, sessionId, EventType.CREATE, session.getState());
		publish(sessionId.toString(), event, hibernateSession);	
	}

	@PUT
	@Path("{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response put(Session requestSession, @PathParam("id") UUID sessionId, @Context SecurityContext sc) {
		
				    				
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		requestSession.setSessionId(sessionId);
		
		// checks the authorization and verifies that the session exists
		Session dbSession = ruleTable.checkAuthorizationForSessionReadWrite(sc, sessionId);
		
		// get the state before this object is updated
		SessionState dbSessionState = dbSession.getState();
		
		requestSession.setAccessed(Instant.now());
		
		update(requestSession, getHibernate().session());
		
		// allow client to change the state from IMPORT to TEMPORARY without changing it directly to READY
		if (SessionState.IMPORT != dbSessionState) {
			this.sessionModified(requestSession, getHibernate().session());
		}
		
		return Response.noContent().build();
    }
	
	public void update(Session session, org.hibernate.Session hibernateSession) {
		UUID sessionId = session.getSessionId();
		// persist
		HibernateUtil.update(session, session.getSessionId(), hibernateSession);
		
		SessionEvent event = new SessionEvent(sessionId, ResourceType.SESSION, sessionId, EventType.UPDATE, session.getState());
		publish(sessionId.toString(), event, hibernateSession);
	}

	public void sessionModified(Session session, org.hibernate.Session hibernateSession) {
		if (SessionState.TEMPORARY_UNMODIFIED == session.getState()) {
			setSessionState(session, SessionState.TEMPORARY_MODIFIED, hibernateSession);
		}
	}
	
	public void setSessionState(Session session, SessionState state, org.hibernate.Session hibernateSession) {
		session.setState(state);
		// otherwise Hibernate won't recognize the change
		hibernateSession.detach(session);
		this.update(session, hibernateSession);
	}
	

	@DELETE
    @Path("{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER}) // don't allow Role.UNAUTHENTICATED
	@Transaction
    public Response delete(@PathParam("id") UUID id, @Context SecurityContext sc) {

		// check authorization
		Session session = ruleTable.checkAuthorizationForSessionReadWrite(sc, id);
		
		this.deleteSession(session, getHibernate().session());

		return Response.noContent().build();
    }
	
	private void deleteSession(Session session, org.hibernate.Session hibernateSession) {		
		
		UUID sessionId = session.getSessionId();
		
		/* Run in separate transaction so that others will see the state change immediately.
		 * The method sessionFactory.getCurrentSession() will still return the original 
		 * session, so be careful to use the innerSession only.
		 */
		HibernateUtil.runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(org.hibernate.Session innerSession) {
				setSessionState(session, SessionState.DELETE, innerSession);
				return null;
			}
		}, getHibernate().getSessionFactory(), false);						
		
		/*
		 * All datasets have to be removed first, because the dataset owns the reference between 
		 * the dataset and the session. This also generates the necessary events e.g. to remove
		 * files.  
		 */
		for (Dataset dataset : SessionDatasetResource.getDatasets(hibernateSession, session)) {
			getDatasetResource(sessionId).deleteDataset(dataset, hibernateSession);
		}
		
		// see the note about datasets above
		for (Job job : SessionJobResource.getJobs(hibernateSession, session)) {
			getJobResource(sessionId).deleteJob(job, hibernateSession);
		}
		
		// see the note about datasets above
		RuleResource ruleResource = getRuleResource(sessionId);
		for (Rule rule : ruleResource.getRules(sessionId)) {
			// argument "deleteSessionIfLastRule" is set to false, because it would call this method again
			ruleResource.delete(session, rule, getHibernate().session(), false);
		}
		
		HibernateUtil.delete(session, session.getSessionId(), hibernateSession);
		
		SessionEvent event = new SessionEvent(sessionId, ResourceType.SESSION, null, EventType.DELETE, session.getState());
		publish(sessionId.toString(), event, hibernateSession);
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
	private GenericEntity<List<Session>> toJaxbList(List<Session> result) {
		return new GenericEntity<List<Session>>(result) {};
	}
	
	public HibernateUtil getHibernate() {
		return hibernate;
	}

	public void setPubSubServer(PubSubServer pubSubServer) {
		this.events = pubSubServer;
	}
	
	public void publish(final String topic, final SessionEvent obj, org.hibernate.Session hibernateSession) {
		// publish the event only after the transaction is completed to make 
		// sure that the modifications are visible
		hibernateSession.addEventListeners(new BaseSessionEventListener() {
			@Override
			public void transactionCompletion(boolean successful) {
				// publish the original event
				events.publish(topic, obj);
				
				// global topics for servers
				if (ResourceType.JOB == obj.getResourceType()) {
					events.publish(SessionDbTopicConfig.JOBS_TOPIC, obj);
				}
				// global AUTHORIZATIONS_TOPIC and SESSIONS_TOPIC and DATASETS_TOPIC hasn't been needed yet
			}				
		});	
	}

	public RuleTable getRuleTable() {
		return ruleTable;
	}

	public void deleteSessionIfOrphan(Session session) {
		
		// don't count public read-only rules, because those can't be deleted afterwards
		long count = ruleTable.getRules(session.getSessionId()).stream()
			.filter(r -> r.isReadWrite() && !RuleTable.EVERYONE.equals(r.getUsername()))
			.count();
			
		if (count == 0) {
			logger.debug("last rule deleted, delete the session too");			
			deleteSession(session, getHibernate().session());
		} else {
			logger.debug(count + " rules left, session kept");
		}
	}
}
