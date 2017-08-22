package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.hibernate.Session;
import org.hibernate.criterion.Projections;
import org.hibernate.metadata.ClassMetadata;

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
import fi.csc.chipster.sessiondb.model.TableStats;

public class SessionDbAdminResource extends AdminResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;

	private JerseyStatisticsSource jerseyStats;

	private PubSubServer pubSubStats;

	public SessionDbAdminResource(HibernateUtil hibernate, JerseyStatisticsSource jerseyStats, PubSubServer pubSubServer) {
		super(jerseyStats, pubSubServer);
		this.hibernate = hibernate;
		this.jerseyStats = jerseyStats;
		this.pubSubStats = pubSubServer;
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
			Long rowCount = (Long) this.hibernate.session()
					.createCriteria(table)
					.setProjection(Projections.rowCount()).uniqueResult();
					
			status.put(table.getSimpleName().toLowerCase() + "Count", rowCount);
		}
		
		// its not easy to reuse the super class method, because these return a response
		status.putAll(AdminResource.getSystemStats());
		status.putAll(jerseyStats.getStatus());
		status.putAll(pubSubStats.getStatus());			
	
		return status;
		
    }
	
	@GET
	@Path("topics")
	@RolesAllowed(Role.MONITORING)
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getClients(@Context SecurityContext sc) {
		
		return Response.ok(pubSubStats.getTopics()).build();		
    }

	
	@GET
    @Path("tables")
    @Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed(Role.SESSION_DB)
    @Transaction
    public Response get(@PathParam("id") UUID authorizationId, @Context SecurityContext sc) throws IOException {
    	return Response.ok(getTableStats(hibernate.session())).build();    	
    }
	
	public ArrayList<TableStats> getTableStats(Session hibernateSession) {
		ArrayList<TableStats> tables = new ArrayList<>();
		
		Map<String, ClassMetadata> classMetadata = hibernateSession.getSessionFactory().getAllClassMetadata();

		for (String className : classMetadata.keySet()) {
			Number size = (Number) hibernateSession.createCriteria(className).setProjection(Projections.rowCount()).uniqueResult();;
			TableStats table = new TableStats();
			table.setName(className.substring(className.lastIndexOf(".") + 1));
			table.setSize((long) size);
			tables.add(table);
		}
		
		return tables;
	}
}
