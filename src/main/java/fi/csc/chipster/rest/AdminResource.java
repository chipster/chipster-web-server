
package fi.csc.chipster.rest;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
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
import org.hibernate.criterion.Projections;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path("admin")
public class AdminResource {
	
	public static final String PATH_STATUS = "status";

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;

	private List<Class<?>> dbTables;
	private List<StatusSource> statusSources;
		
    public AdminResource(HibernateUtil hibernate, List<Class<?>> dbTables, StatusSource... stats) {
		this.hibernate = hibernate;
		this.dbTables = dbTables;
		if (stats != null) {
			this.statusSources = Arrays.asList(stats);
		}
	}

	public AdminResource(HibernateUtil hibernate, Class<Token> dbTable, JerseyStatisticsSource statisticsListener) {
		this(hibernate, Arrays.asList(new Class<?>[] {dbTable}), statisticsListener); 
	}

	public AdminResource(StatusSource... stats) {
		this(null, new ArrayList<>(), stats);
	}

	@GET
	@Path("alive")
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getAlive(@Context SecurityContext sc) {
		return Response.ok().build();
	}

	
	@GET
	@Path(PATH_STATUS)
	@RolesAllowed(Role.MONITORING)
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public HashMap<String, Object> getStatus(@Context SecurityContext sc) {
		
		HashMap<String, Object> status = new HashMap<>();
		
		for (Class<?> table : dbTables) {				
			long rowCount = (Long) getHibernate().session()
					.createCriteria(table)
					.setProjection(Projections.rowCount()).uniqueResult();
					
			status.put(table.getSimpleName().toLowerCase() + "Count", rowCount);
			
		}
		
		if (statusSources != null) {
			for (StatusSource src : statusSources) {
				status.putAll(src.getStatus());
			}
		}
		
		status.putAll(getSystemStats());
	
		return status;
		
    }	
	
	public static HashMap<String, Object> getSystemStats() {
		
		HashMap<String, Object> status = new HashMap<>();
		
		status.put("load", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
		status.put("cores", Runtime.getRuntime().availableProcessors());

		status.put("diskTotal", new File(".").getTotalSpace());
		status.put("diskFree", new File(".").getFreeSpace());
		
		return status;
	}

	private HibernateUtil getHibernate() {
		return hibernate;
	}
}
