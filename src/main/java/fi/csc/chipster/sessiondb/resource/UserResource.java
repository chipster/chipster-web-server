package fi.csc.chipster.sessiondb.resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;

@Path("users")
public class UserResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;
	
	public UserResource(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}
    
	@GET
	@RolesAllowed({Role.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
    public Response getAll(@Context SecurityContext sc) {

		@SuppressWarnings("unchecked")
		List<String> users = hibernate.session()
				.createQuery("select distinct(username) from Rule")
				.list();		
		
		return Response.ok(users).build();
    }
	
    @GET
    @Path("{username}/quota")
    @RolesAllowed({Role.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response getQuota(@PathParam("username") String username, @Context SecurityContext sc) {
    	
    	@SuppressWarnings("unchecked")
		List<Rule> rules = hibernate.session()
    			.createQuery("from Rule where username=:username")
    			.setParameter("username", username)
    			.list();    	    		
    	
    	List<Dataset> datasets = rules.stream()
	    	.map(rule -> rule.getSession())	    	
	    	.flatMap(session -> session.getDatasets().values().stream())
	    	.collect(Collectors.toList());
    	
    	Map<UUID, File> uniqueFiles = datasets.stream()
	    	.map(dataset -> dataset.getFile())
	    	.filter(file -> file != null)
	    	.collect(Collectors.toMap(file -> file.getFileId(), file -> file));
	    
	    Long size = uniqueFiles.values().stream()	
	    	.collect(Collectors.summingLong(file -> file.getSize()));       
    	
		Long readWriteSessions = (Long)hibernate.session()
				.createQuery("select count(*) from Rule where username=:username and readWrite=true")
				.setParameter("username", username)
				.uniqueResult();
		
		Long readOnlySessions = (Long)hibernate.session()
				.createQuery("select count(*) from Rule where username=:username and readWrite=false")
				.setParameter("username", username)
				.uniqueResult();
    	
    	HashMap<String, Object> responseObj = new HashMap<String, Object>() {
            {
            	put("username", username);
                put("readWriteSessions", readWriteSessions);
                put("readOnlySessions", readOnlySessions);
                put("size", size);
            }
        };
        
        return Response.ok(responseObj).build();
    }
    
    @GET
    @Path("{username}/sessions")
    @RolesAllowed({Role.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response getSessions(@PathParam("username") String username, @Context SecurityContext sc) {
    	
    	@SuppressWarnings("unchecked")
		List<Rule> rules = hibernate.session()
    			.createQuery("from Rule where username=:username")
    			.setParameter("username", username)
    			.list();
    	
    	List<Session> sessions = rules.stream()
	    	.map(rule -> rule.getSession())
	    	.collect(Collectors.toList());
    	
    	List<HashMap<String, Object>> sessionSizes = new ArrayList<>();
    	
    	for (Session session : sessions) {
    		
    		long sessionSize = session.getDatasets().values().stream()
    		.map(dataset -> dataset.getFile())
    		.filter(file -> file != null)
	    	.collect(Collectors.toMap(file -> file.getFileId(), file -> file))
	    	.values().stream()
	    	.collect(Collectors.summingLong(file -> file.getSize()));
    		
    		long datasetsCount = session.getDatasets().size();
    		long jobCount = session.getJobs().size();
    		long inputCount = session.getJobs().values().stream()
    				.flatMap(job -> job.getInputs().stream())
    				.count();
    		long parameterCount = session.getJobs().values().stream()
    				.flatMap(job -> job.getParameters().stream())
    				.count();
    		long metadataCount = session.getDatasets().values().stream()
    				.flatMap(dataset -> dataset.getMetadata().stream())
    				.count();
    		
    		sessionSizes.add(new HashMap<String, Object>() {
                {
                	put("sessionId", session.getSessionId());
                	put("name", session.getName());
                    put("size", sessionSize);
                    put("datasetCount", datasetsCount);
                    put("jobCount", jobCount);
                    put("inputCount", inputCount);
                    put("parameterCount", parameterCount);
                    put("metadataCount", metadataCount);
                }                
            });
    	}
        
        return Response.ok(sessionSizes).build();
    }
}
