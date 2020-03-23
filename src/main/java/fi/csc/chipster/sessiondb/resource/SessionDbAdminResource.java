package fi.csc.chipster.sessiondb.resource;

import java.util.ArrayList;
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
import fi.csc.chipster.sessiondb.model.File;

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
	@Path("storages")
	@RolesAllowed({Role.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Object getStorages(@Context SecurityContext sc) {
				
		@SuppressWarnings("unchecked")
		List<Object[]> dbStorages = hibernate.session().createQuery("select storage, count(*), sum(size) from " + File.class.getSimpleName() + " group by storage").getResultList();
		
		ArrayList<HashMap<String, Object>> storages = new ArrayList<>();
		
		for ( Object[] dbStorage : dbStorages) {
			storages.add(new HashMap<String, Object>() {{
				put("storageId", dbStorage[0]);
				put("fileCount", dbStorage[1]);
				put("fileBytes", dbStorage[2]);
			}});
		}
		
		return storages;
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
