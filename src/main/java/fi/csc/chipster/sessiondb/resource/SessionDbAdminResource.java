package fi.csc.chipster.sessiondb.resource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.JerseyStatisticsSource;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.DatasetToken;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.MetadataEntry;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;

public class SessionDbAdminResource extends AdminResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;

	private PubSubServer pubSubServer;

	public SessionDbAdminResource(HibernateUtil hibernate, JerseyStatisticsSource jerseyStats, PubSubServer pubSubServer) {
		super(jerseyStats, pubSubServer);
		this.hibernate = hibernate;
		this.pubSubServer = pubSubServer;
	}
	
	@GET
	@Path(AdminResource.PATH_STATUS)
	@RolesAllowed(Role.MONITORING)
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public HashMap<String, Object> getStatus(@Context SecurityContext sc) {
		
		HashMap<String, Object> status = super.getStatus(sc);
		
		List<Class<?>> dbTables = Arrays.asList(new Class<?>[] { 
			Session.class, Dataset.class, Job.class, DatasetToken.class, File.class, 
			Input.class, MetadataEntry.class, Parameter.class, Rule.class });
			
		for (Class<?> table : dbTables) {				
			
			long rowCount = getRowCount(table, hibernate);
					
			status.put(table.getSimpleName().toLowerCase() + "Count", rowCount);
		}			
	
		return status;
		
    }
	
	@GET
	@Path("topics")
	@RolesAllowed(Role.MONITORING)
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getClients(@Context SecurityContext sc) {
		
		return Response.ok(pubSubServer.getTopics()).build();		
    }
}
