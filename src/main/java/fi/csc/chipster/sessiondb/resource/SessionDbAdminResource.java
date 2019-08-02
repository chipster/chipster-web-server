package fi.csc.chipster.sessiondb.resource;

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

public class SessionDbAdminResource extends AdminResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;

	private PubSubServer pubSubServer;

	private List<Class<?>> hibernateClasses;

	public SessionDbAdminResource(HibernateUtil hibernate, JerseyStatisticsSource jerseyStats, PubSubServer pubSubServer, List<Class<?>> hibernateClasses) {
		super(jerseyStats, pubSubServer);
		this.hibernate = hibernate;
		this.pubSubServer = pubSubServer;
		this.hibernateClasses = hibernateClasses;
	}
	
	@GET
	@Path(AdminResource.PATH_STATUS)
	@RolesAllowed({Role.MONITORING, Role.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public HashMap<String, Object> getStatus(@Context SecurityContext sc) {
		
		HashMap<String, Object> status = super.getStatus(sc);		
			
		for (Class<?> table : hibernateClasses) {				
			
			long rowCount = getRowCount(table, hibernate);
					
			status.put(table.getSimpleName().toLowerCase() + "Count", rowCount);
		}			
	
		return status;
		
    }
	
	@GET
	@Path("topics")
	@RolesAllowed({Role.MONITORING, Role.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getClients(@Context SecurityContext sc) {
		
		return Response.ok(pubSubServer.getTopics()).build();		
    }
}
