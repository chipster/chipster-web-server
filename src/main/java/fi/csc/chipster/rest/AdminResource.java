
package fi.csc.chipster.rest;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import javax.annotation.security.RolesAllowed;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
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
	private HashMap<String, File> fileSystems = new HashMap<>();
		
    @SuppressWarnings({ "unchecked", "rawtypes" }) // Jersey logs a warning if the dbTables is typed 
	public AdminResource(HibernateUtil hibernate, List dbTables, StatusSource... stats) {
		this.hibernate = hibernate;
		this.dbTables = dbTables;
		if (stats != null) {
			this.statusSources = Arrays.asList(stats);
		}
		this.fileSystems.put("root", new File("."));
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
	@RolesAllowed({Role.MONITORING, Role.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public HashMap<String, Object> getStatus(@Context SecurityContext sc) {
		
		HashMap<String, Object> status = new HashMap<>();
		
		for (Class<?> table : dbTables) {				
			
			long rowCount = getRowCount(table, hibernate);
					
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
	
	public static long getRowCount(Class<?> table, HibernateUtil hibernate) {
		CriteriaBuilder qb = hibernate.session().getCriteriaBuilder();
		CriteriaQuery<Long> cq = qb.createQuery(Long.class);
		cq.select(qb.count(cq.from(table)));
		return hibernate.session().createQuery(cq).getSingleResult();
	}

	public HashMap<String, Object> getSystemStats() {
		
		HashMap<String, Object> status = new HashMap<>();
		
		status.put("load", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
		status.put("cores", Runtime.getRuntime().availableProcessors());
		status.put("memoryJvmMax", Runtime.getRuntime().maxMemory());
		status.put("memoryJvmFree", Runtime.getRuntime().freeMemory());

		for (String name : fileSystems.keySet()) {
			collectDiskStats(status, fileSystems.get(name), name);			
		}		
		
		return status;
	}

	private static void collectDiskStats(HashMap<String, Object> status, File file, String name) {
		status.put("diskTotal,fs=" + name, file.getTotalSpace());
		status.put("diskFree,fs=" + name, file.getFreeSpace());
	}

	public void addFileSystem(String name, File dir) {
		this.fileSystems.put(name, dir);
	}
}
