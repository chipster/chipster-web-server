
package fi.csc.chipster.rest;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

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
import fi.csc.chipster.sessiondb.StatisticsListener;

@Path("admin")
public class GenericAdminResource {
	
	public static final String VALUE_OK = "OK";
	public static final String KEY_STATUS = "status";
	public static final String PATH_STATUS = "status";

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;

	private List<Class<?>> dbTables;
	private StatisticsListener statisticsListener;
		
    public GenericAdminResource(HibernateUtil hibernate, List<Class<?>> dbTables, StatisticsListener statisticsListener) {
		this.hibernate = hibernate;
		this.dbTables = dbTables;
		this.statisticsListener = statisticsListener;
	}

	public GenericAdminResource(HibernateUtil hibernate, Class<Token> dbTable, StatisticsListener statisticsListener) {
		this(hibernate, Arrays.asList(new Class<?>[] {dbTable}), statisticsListener); 
	}

	public GenericAdminResource(StatisticsListener statisticsListener) {
		this(null, new ArrayList<>(), statisticsListener);
	}

	@GET
	@Path(PATH_STATUS)
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getStatus(@Context SecurityContext sc) {
		
		HashMap<String, Object> status = new HashMap<>();
		
		if (sc.isUserInRole(Role.MONITORING)) {
			
			for (Class<?> table : dbTables) {				
				long rowCount = (Long) getHibernate().session()
						.createCriteria(table)
						.setProjection(Projections.rowCount()).uniqueResult();
						
				status.put(table.getSimpleName().toLowerCase() + "Count", rowCount);
				
			}
			
			if (statisticsListener != null) {
				status.putAll(statisticsListener.getLatestStatistics());
			}
			
			status.putAll(getSystemStats());
		}
		
		status.put(KEY_STATUS, VALUE_OK);
	
		return Response.ok(status).build();
		
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
