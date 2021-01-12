package fi.csc.chipster.sessiondb.resource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

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
	
	private static Logger logger = LogManager.getLogger();
	
//	private final static String SQL_ORPHAN_FILES =     "from File f    left join Dataset d on f.fileId = d.file                       where d.file is null";
//	private final static String SQL_ORPHAN_DATASETS =  "from Dataset d left join Session s on d.datasetIdPair.sessionId = s.sessionId where s.sessionId is null";
//	private final static String SQL_ORPHAN_JOBS =      "from Job j     left join Session s on j.jobIdPair.sessionId = s.sessionId     where s.sessionId is null";
//	private final static String SQL_ORPHAN_RULES =     "from Rule r    left join Session s on r.session.sessionId = s.sessionId       where s.sessionId is null";
//	private final static String SQL_ORPHAN_SESSIONS =  "from Session s left join Rule r    on s = r.session                           where r.session is null";
	
	private final static String SQL_ORPHAN_FILES =     "from file f    where not exists ( select from dataset d where f.fileid = d.fileid)";
	private final static String SQL_ORPHAN_DATASETS =  "from dataset d where not exists ( select from session s where d.sessionid = s.sessionid)";
	private final static String SQL_ORPHAN_JOBS =      "from job j     where not exists ( select from session s where j.sessionid = s.sessionid)";
	private final static String SQL_ORPHAN_SESSIONS =  "from session s where not exists ( select from rule r    where s.sessionid = r.sessionid)";
	private final static String SQL_ORPHAN_RULES =     "from rule r    where not exists ( select from session s where r.sessionid = s.sessionid)";
	
	private HibernateUtil hibernate;

	private PubSubServer pubSubServer;

	private List<Class<?>> hibernateClasses;

	/**
	 * @param hibernate
	 * @param jerseyStats
	 * @param pubSubServer
	 * @param classes as an array, because Jersey doesn't like wildcard types (produces a log warning "Not resolvable to a concrete type"). We don't care about it, because we instantiate the class ourselves.
	 */
	public SessionDbAdminResource(HibernateUtil hibernate, JerseyStatisticsSource jerseyStats, PubSubServer pubSubServer, @SuppressWarnings("rawtypes") Class[] classes) {
		super(jerseyStats, pubSubServer);
		this.hibernate = hibernate;
		this.pubSubServer = pubSubServer;
		this.hibernateClasses = Arrays.asList(classes);
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
	
	@POST
	@Path("check-orphans")
	@RolesAllowed({Role.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Object checkOrphans(@Context SecurityContext sc) {
		
		final String SELECT_COUNT = "select count(*) ";
		
		BigInteger orphanFiles = (BigInteger) hibernate.session().createNativeQuery(SELECT_COUNT + SQL_ORPHAN_FILES).getSingleResult();
		BigInteger orphanDatasets = (BigInteger) hibernate.session().createNativeQuery(SELECT_COUNT + SQL_ORPHAN_DATASETS).getSingleResult();
		BigInteger orphanJobs = (BigInteger) hibernate.session().createNativeQuery(SELECT_COUNT + SQL_ORPHAN_JOBS).getSingleResult();
		BigInteger orphanRules = (BigInteger) hibernate.session().createNativeQuery(SELECT_COUNT + SQL_ORPHAN_RULES).getSingleResult();
		BigInteger orphanSessions = (BigInteger) hibernate.session().createNativeQuery(SELECT_COUNT + SQL_ORPHAN_SESSIONS).getSingleResult();
		
		HashMap<String, Object> orphans = new HashMap<String, Object>() {{
			put("orphanFiles", orphanFiles);
			put("orphanDatasets", orphanDatasets);
			put("orphanJobs", orphanJobs);
			put("orphanRules", orphanRules);
			put("orphanSessions", orphanSessions);
		}};
		
		for (String key : orphans.keySet()) {
			logger.info("check orphans: " + key + " " + orphans.get(key));
		}
		
		return orphans;
    }
	
	@POST
	@Path("delete-orphans")
	@RolesAllowed({Role.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Object deleteOrphans(@Context SecurityContext sc) {
		
		final String DELETE = "delete ";
		
		int orphanSessions = hibernate.session().createNativeQuery(DELETE + SQL_ORPHAN_SESSIONS).executeUpdate();
		int orphanRules = hibernate.session().createNativeQuery(DELETE + SQL_ORPHAN_RULES).executeUpdate();
		int orphanJobs = hibernate.session().createNativeQuery(DELETE + SQL_ORPHAN_JOBS).executeUpdate();
		int orphanDatasets = hibernate.session().createNativeQuery(DELETE + SQL_ORPHAN_DATASETS).executeUpdate();
		int orphanFiles = hibernate.session().createNativeQuery(DELETE + SQL_ORPHAN_FILES).executeUpdate();
		
		HashMap<String, Object> orphans = new HashMap<String, Object>() {{
			put("orphanFiles", orphanFiles);
			put("orphanDatasets", orphanDatasets);
			put("orphanJobs", orphanJobs);
			put("orphanRules", orphanRules);
			put("orphanSessions", orphanSessions);
		}};
		
		for (String key : orphans.keySet()) {
			logger.info("deleted orphans: " + key + " " + orphans.get(key));
		}
		
		return orphans;
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
