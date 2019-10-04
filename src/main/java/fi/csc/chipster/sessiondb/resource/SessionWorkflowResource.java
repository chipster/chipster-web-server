package fi.csc.chipster.sessiondb.resource;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.security.RolesAllowed;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.sessiondb.model.WorkflowPlan;
import fi.csc.chipster.sessiondb.model.WorkflowPlanIdPair;
import fi.csc.chipster.sessiondb.model.WorkflowRun;
import fi.csc.chipster.sessiondb.model.WorkflowRunIdPair;

public class SessionWorkflowResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	final private UUID sessionId;

	private SessionResource sessionResource;
	
	public SessionWorkflowResource() {
		sessionId = null;
	}
	
	public SessionWorkflowResource(SessionResource sessionResource, UUID id) {
		this.sessionResource = sessionResource;
		this.sessionId = id;
	}
	
    @GET
    @Path("plans/{id}")    
    @RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response getPlan(@PathParam("id") UUID jobId, @Context SecurityContext sc) {
    	
    	// checks authorization
    	Session session = sessionResource.getRuleTable().checkAuthorizationForSessionRead(sc, sessionId, true);    	
    	
    	WorkflowPlan result = getWorkflowPlan(sessionId, jobId, getHibernate().session());
    	    	
    	if (result == null || !result.getSessionId().equals(session.getSessionId())) {
    		throw new NotFoundException();
    	}
    	
   		return Response.ok(result).build();
    }
    
    public static WorkflowPlan getWorkflowPlan(UUID sessionId, UUID workflowPlanId, org.hibernate.Session hibernateSession) {
    	
    	return hibernateSession.get(WorkflowPlan.class, new WorkflowPlanIdPair(sessionId, workflowPlanId));    	
    }
    
    @GET
    @Path("runs/{id}")    
    @RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response getRun(@PathParam("id") UUID jobId, @Context SecurityContext sc) {
    	
    	// checks authorization
    	Session session = sessionResource.getRuleTable().checkAuthorizationForSessionRead(sc, sessionId, true);    	
    	
    	WorkflowRun result = getWorkflowRun(sessionId, jobId, getHibernate().session());
    	    	
    	if (result == null || !result.getSessionId().equals(session.getSessionId())) {
    		throw new NotFoundException();
    	}
    	
   		return Response.ok(result).build();
    }
    
    public static WorkflowRun getWorkflowRun(UUID sessionId, UUID workflowRunId, org.hibernate.Session hibernateSession) {
    	
    	return hibernateSession.get(WorkflowRun.class, new WorkflowRunIdPair(sessionId, workflowRunId));    	
    }
    
	@GET
	@Path("runs")
	@RolesAllowed({ Role.CLIENT, Role.SERVER, Role.SESSION_DB_TOKEN})
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAllRuns(@Context SecurityContext sc) {
		
		// checks authorization
		Session session = sessionResource.getRuleTable().checkAuthorizationForSessionRead(sc, sessionId);
		
		List<WorkflowRun> result = getRuns(getHibernate().session(), session);

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(result).build();
    }
	
	public static List<WorkflowRun> getRuns(org.hibernate.Session hibernateSession, Session session) {
		
		CriteriaBuilder cb = hibernateSession.getCriteriaBuilder();
		CriteriaQuery<WorkflowRun> c = cb.createQuery(WorkflowRun.class);
		Root<WorkflowRun> r = c.from(WorkflowRun.class);
		c.select(r);		
		c.where(cb.equal(r.get("workflowRunIdPair").get("sessionId"), session.getSessionId()));		
		List<WorkflowRun> runs = HibernateUtil.getEntityManager(hibernateSession).createQuery(c).getResultList();	
				
		return runs;
	}
	
	@GET
	@Path("plans")
	@RolesAllowed({ Role.CLIENT, Role.SERVER, Role.SESSION_DB_TOKEN})
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAllPlans(@Context SecurityContext sc) {
		
		// checks authorization
		Session session = sessionResource.getRuleTable().checkAuthorizationForSessionRead(sc, sessionId);
		
		List<WorkflowPlan> result = getPlans(getHibernate().session(), session);

		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(result).build();
    }
	
	public static List<WorkflowPlan> getPlans(org.hibernate.Session hibernateSession, Session session) {
		
		CriteriaBuilder cb = hibernateSession.getCriteriaBuilder();
		CriteriaQuery<WorkflowPlan> c = cb.createQuery(WorkflowPlan.class);
		Root<WorkflowPlan> r = c.from(WorkflowPlan.class);
		c.select(r);		
		c.where(cb.equal(r.get("workflowPlanIdPair").get("sessionId"), session.getSessionId()));		
		List<WorkflowPlan> plans = HibernateUtil.getEntityManager(hibernateSession).createQuery(c).getResultList();	
				
		return plans;
	}

	@POST
	@Path("plans/" + RestUtils.PATH_ARRAY)
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response postPlanArray(WorkflowPlan[] plans, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		List<UUID> ids = postPlanList(Arrays.asList(plans), uriInfo, sc);		
		
		ObjectNode json = RestUtils.getArrayResponse("workflowPlans", "workflowPlanId", ids);		
		
		return Response.created(uriInfo.getRequestUri()).entity(json).build();
	}
	
	@POST
	@Path("runs/" + RestUtils.PATH_ARRAY)
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response postRunArray(WorkflowRun[] runs, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		List<UUID> ids = postRunList(Arrays.asList(runs), uriInfo, sc);		
		
		ObjectNode json = RestUtils.getArrayResponse("workflowRuns", "workflowRunId", ids);		
		
		return Response.created(uriInfo.getRequestUri()).entity(json).build();
	}
	
	@POST
	@Path("plans")
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response postPlan(WorkflowPlan plan, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		
		List<UUID> ids = postPlanList(Arrays.asList(plan), uriInfo, sc);
		UUID id = ids.get(0);
		
		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();
		
		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("workflowPlanId", id.toString());		
		
		return Response.created(uri).entity(json).build();
	}
	
	@POST
	@Path("runs")
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response postRun(WorkflowRun run, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		
		List<UUID> ids = postRunList(Arrays.asList(run), uriInfo, sc);
		UUID id = ids.get(0);
		
		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();
		
		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("workflowRunId", id.toString());		
		
		return Response.created(uri).entity(json).build();
	}
		
	public List<UUID> postPlanList(List<WorkflowPlan> plans, @Context UriInfo uriInfo, @Context SecurityContext sc) {
	
		for (WorkflowPlan plan : plans) {			
			// client's are allowed to set the plan id to preserve the object references within the session
			UUID planId = plan.getWorkflowPlanId();
			if (planId == null) {
				planId = RestUtils.createUUID();
			}
			
			// make sure a hostile client doesn't set the session
			if (plan.getSessionId() != null && !sessionId.equals(plan.getSessionId())) {
				throw new BadRequestException("different sessionId in the job object and in the url");
			}
			plan.setWorkflowPlanIdPair(sessionId, planId);
			plan.setCreated(Instant.now());
		}		
		
		Session session = sessionResource.getRuleTable().checkAuthorizationForSessionReadWrite(sc, sessionId);
		
		for (WorkflowPlan plan : plans) {
			createPlan(plan, getHibernate().session());
		}
		
		sessionResource.sessionModified(session, getHibernate().session());
		
		return plans.stream()
				.map(j -> j.getWorkflowPlanId())
				.collect(Collectors.toList());
    }

	public void createPlan(WorkflowPlan plan, org.hibernate.Session hibernateSession) {
		HibernateUtil.persist(plan, hibernateSession);
		SessionEvent event = new SessionEvent(sessionId, ResourceType.WORKFLOW_PLAN, plan.getWorkflowPlanId(), EventType.CREATE);
		sessionResource.publish(sessionId.toString(), event, hibernateSession);
	}

	public List<UUID> postRunList(List<WorkflowRun> runs, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		
		for (WorkflowRun run : runs) {			
			// client's are allowed to set the plan id to preserve the object references within the session
			UUID runId = run.getWorkflowRunId();
			if (runId == null) {
				runId = RestUtils.createUUID();
			}
			
			// make sure a hostile client doesn't set the session
			if (run.getSessionId() != null && !sessionId.equals(run.getSessionId())) {
				throw new BadRequestException("different sessionId in the job object and in the url");
			}
			run.setWorkflowRunIdPair(sessionId, runId);
			run.setCreated(Instant.now());
		}		
		
		Session session = sessionResource.getRuleTable().checkAuthorizationForSessionReadWrite(sc, sessionId);
		
		for (WorkflowRun run : runs) {
			createRun(run, getHibernate().session());
		}
		
		sessionResource.sessionModified(session, getHibernate().session());
		
		return runs.stream()
				.map(j -> j.getWorkflowRunId())
				.collect(Collectors.toList());
    }

	public void createRun(WorkflowRun run, org.hibernate.Session hibernateSession) {
		HibernateUtil.persist(run, hibernateSession);
		SessionEvent event = new SessionEvent(sessionId, ResourceType.WORKFLOW_RUN, run.getWorkflowRunId(), EventType.CREATE);
		sessionResource.publish(sessionId.toString(), event, hibernateSession);
	}

	@PUT
	@Path("plans/{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response putPlan(WorkflowPlan plan, @PathParam("id") UUID planId, @Context SecurityContext sc) {
				    		
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		plan.setWorkflowPlanIdPair(sessionId, planId);
		
		/*
		 * Checks that
		 * - user has write authorization for the session
		 * - the session contains this plan
		 */
		Session session = sessionResource.getRuleTable().checkAuthorizationForSessionReadWrite(sc, sessionId);
		WorkflowPlan dbPlan = getWorkflowPlan(sessionId, planId, getHibernate().session());
		if (dbPlan == null || !dbPlan.getSessionId().equals(session.getSessionId())) {
			throw new NotFoundException("workflow plan doesn't exist");
		}
		
		updatePlan(plan, getHibernate().session());
		
		sessionResource.sessionModified(session, getHibernate().session());
		
		return Response.noContent().build();
    }
	
	public void updatePlan(WorkflowPlan plan, org.hibernate.Session hibernateSession) {
		HibernateUtil.update(plan, plan.getWorkflowPlanIdPair(), hibernateSession);
		SessionEvent event = new SessionEvent(sessionId, ResourceType.WORKFLOW_PLAN, plan.getWorkflowPlanId(), EventType.UPDATE);
		sessionResource.publish(sessionId.toString(), event, hibernateSession);
	}

	@PUT
	@Path("runs/{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response putRun(WorkflowRun run, @PathParam("id") UUID runId, @Context SecurityContext sc) {
				    		
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		run.setWorkflowRunIdPair(sessionId, runId);
		
		/*
		 * Checks that
		 * - user has write authorization for the session
		 * - the session contains this dataset
		 */
		Session session = sessionResource.getRuleTable().checkAuthorizationForSessionReadWrite(sc, sessionId);
		WorkflowRun dbRun = getWorkflowRun(sessionId, runId, getHibernate().session());
		if (dbRun == null || !dbRun.getSessionId().equals(session.getSessionId())) {
			throw new NotFoundException("workflow run doesn't exist");
		}
		// make sure a hostile client doesn't change the createdBy username
		run.setCreatedBy(dbRun.getCreatedBy());
		
		updateRun(run, getHibernate().session());
		
		sessionResource.sessionModified(session, getHibernate().session());
		
		return Response.noContent().build();
    }
	
	public void updateRun(WorkflowRun run, org.hibernate.Session hibernateSession) {
		HibernateUtil.update(run, run.getWorkflowRunIdPair(), hibernateSession);
		SessionEvent event = new SessionEvent(sessionId, ResourceType.WORKFLOW_RUN, run.getWorkflowRunId(), EventType.UPDATE, run.getState());
		sessionResource.publish(sessionId.toString(), event, hibernateSession);
	}

	@DELETE
    @Path("plans/{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
	@Transaction
    public Response deletePlan(@PathParam("id") UUID planId, @Context SecurityContext sc) {

		// checks authorization
		Session session = sessionResource.getRuleTable().checkAuthorizationForSessionReadWrite(sc, sessionId);
		WorkflowPlan dbPlan = getWorkflowPlan(sessionId, planId, getHibernate().session());
		
		if (dbPlan == null || !dbPlan.getSessionId().equals(session.getSessionId())) {
			throw new NotFoundException("workflow plan not found");
		}
		
		deletePlan(dbPlan, getHibernate().session());
		
		sessionResource.sessionModified(session, getHibernate().session());
		
		return Response.noContent().build();
    }
	
	public void deletePlan(WorkflowPlan plan, org.hibernate.Session hibernateSession) {
		HibernateUtil.delete(plan, plan.getWorkflowPlanIdPair(), hibernateSession);
		SessionEvent event = new SessionEvent(sessionId, ResourceType.WORKFLOW_PLAN, plan.getWorkflowPlanId(), EventType.DELETE);
		sessionResource.publish(sessionId.toString(), event, hibernateSession);
	}
	
	@DELETE
    @Path("runs/{id}")
	@RolesAllowed({ Role.CLIENT, Role.SERVER }) // don't allow Role.UNAUTHENTICATED
	@Transaction
    public Response deleteRun(@PathParam("id") UUID runId, @Context SecurityContext sc) {

		// checks authorization
		Session session = sessionResource.getRuleTable().checkAuthorizationForSessionReadWrite(sc, sessionId);
		WorkflowRun dbRun = getWorkflowRun(sessionId, runId, getHibernate().session());
		
		if (dbRun == null || !dbRun.getSessionId().equals(session.getSessionId())) {
			throw new NotFoundException("workflow run not found");
		}
		
		deleteRun(dbRun, getHibernate().session());
		
		sessionResource.sessionModified(session, getHibernate().session());
		
		return Response.noContent().build();
    }
	
	public void deleteRun(WorkflowRun run, org.hibernate.Session hibernateSession) {
		HibernateUtil.delete(run, run.getWorkflowRunIdPair(), hibernateSession);
		SessionEvent event = new SessionEvent(sessionId, ResourceType.WORKFLOW_RUN, run.getWorkflowRunId(), EventType.DELETE, run.getState());
		sessionResource.publish(sessionId.toString(), event, hibernateSession);
	}

	private HibernateUtil getHibernate() {
		return sessionResource.getHibernate();
	}
}
