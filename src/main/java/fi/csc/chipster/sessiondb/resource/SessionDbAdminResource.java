package fi.csc.chipster.sessiondb.resource;

import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.JerseyStatisticsSource;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.News;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

public class SessionDbAdminResource extends AdminResource {

	private static Logger logger = LogManager.getLogger();
	public static final String PATH_USERS_SESSIONS = "users/sessions";
	public static final String PATH_USERS_QUOTA = "users/quota";

//	private final static String SQL_ORPHAN_FILES =     "from File f    left join Dataset d on f.fileId = d.file                       where d.file is null";
//	private final static String SQL_ORPHAN_DATASETS =  "from Dataset d left join Session s on d.datasetIdPair.sessionId = s.sessionId where s.sessionId is null";
//	private final static String SQL_ORPHAN_JOBS =      "from Job j     left join Session s on j.jobIdPair.sessionId = s.sessionId     where s.sessionId is null";
//	private final static String SQL_ORPHAN_RULES =     "from Rule r    left join Session s on r.session.sessionId = s.sessionId       where s.sessionId is null";
//	private final static String SQL_ORPHAN_SESSIONS =  "from Session s left join Rule r    on s = r.session                           where r.session is null";

	private final static String SQL_ORPHAN_FILES = "from file f    where not exists ( select from dataset d where f.fileid = d.fileid)";
	private final static String SQL_ORPHAN_DATASETS = "from dataset d where not exists ( select from session s where d.sessionid = s.sessionid)";
	private final static String SQL_ORPHAN_JOBS = "from job j     where not exists ( select from session s where j.sessionid = s.sessionid)";
	private final static String SQL_ORPHAN_SESSIONS = "from session s where not exists ( select from rule r    where s.sessionid = r.sessionid)";
	private final static String SQL_ORPHAN_RULES = "from rule r    where not exists ( select from session s where r.sessionid = s.sessionid)";

	private HibernateUtil hibernate;

	private PubSubServer pubSubServer;

	private NewsApi newsApi;
	private SessionDbApi sessionDbApi;
	private RuleTable ruleTable;

	/**
	 * @param hibernate
	 * @param jerseyStats
	 * @param pubSubServer
	 * @param classes      as an array, because Jersey doesn't like wildcard types
	 *                     (produces a log warning "Not resolvable to a concrete
	 *                     type"). We don't care about it, because we instantiate
	 *                     the class ourselves.
	 * @param newsApi
	 * @param ruleTable 
	 */
	public SessionDbAdminResource(HibernateUtil hibernate, JerseyStatisticsSource jerseyStats,
			PubSubServer pubSubServer, @SuppressWarnings("rawtypes") Class[] classes, NewsApi newsApi, SessionDbApi sessionDbApi, RuleTable ruleTable) {
		super(hibernate, Arrays.asList(classes), jerseyStats, pubSubServer);
		this.hibernate = hibernate;
		this.pubSubServer = pubSubServer;
		this.newsApi = newsApi;
		this.sessionDbApi = sessionDbApi;
		this.ruleTable = ruleTable;
	}

	@GET
	@Path(AdminResource.PATH_STATUS)
	@RolesAllowed({ Role.MONITORING, Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public HashMap<String, Object> getStatus(@Context SecurityContext sc) {

		HashMap<String, Object> status = super.getStatus(sc);

		return status;

	}

	@GET
	@Path("storages")
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Object getStorages(@Context SecurityContext sc) {

		@SuppressWarnings("unchecked")
		List<Object[]> dbStorages = hibernate.session()
				.createQuery(
						"select storage, count(*), sum(size) from " + File.class.getSimpleName() + " group by storage")
				.getResultList();

		ArrayList<HashMap<String, Object>> storages = new ArrayList<>();

		for (Object[] dbStorage : dbStorages) {
			storages.add(new HashMap<String, Object>() {
				{
					put("storageId", dbStorage[0]);
					put("fileCount", dbStorage[1]);
					put("fileBytes", dbStorage[2]);
				}
			});
		}

		return storages;
	}

	@POST
	@Path("check-orphans")
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Object checkOrphans(@Context SecurityContext sc) {

		final String SELECT_COUNT = "select count(*) ";

		BigInteger orphanFiles = (BigInteger) hibernate.session().createNativeQuery(SELECT_COUNT + SQL_ORPHAN_FILES)
				.getSingleResult();
		BigInteger orphanDatasets = (BigInteger) hibernate.session()
				.createNativeQuery(SELECT_COUNT + SQL_ORPHAN_DATASETS).getSingleResult();
		BigInteger orphanJobs = (BigInteger) hibernate.session().createNativeQuery(SELECT_COUNT + SQL_ORPHAN_JOBS)
				.getSingleResult();
		BigInteger orphanRules = (BigInteger) hibernate.session().createNativeQuery(SELECT_COUNT + SQL_ORPHAN_RULES)
				.getSingleResult();
		BigInteger orphanSessions = (BigInteger) hibernate.session()
				.createNativeQuery(SELECT_COUNT + SQL_ORPHAN_SESSIONS).getSingleResult();

		HashMap<String, Object> orphans = new HashMap<String, Object>() {
			{
				put("orphanFiles", orphanFiles);
				put("orphanDatasets", orphanDatasets);
				put("orphanJobs", orphanJobs);
				put("orphanRules", orphanRules);
				put("orphanSessions", orphanSessions);
			}
		};

		for (String key : orphans.keySet()) {
			logger.info("check orphans: " + key + " " + orphans.get(key));
		}

		return orphans;
	}

	@POST
	@Path("delete-orphans")
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Object deleteOrphans(@Context SecurityContext sc) {

		final String DELETE = "delete ";

		int orphanSessions = hibernate.session().createNativeQuery(DELETE + SQL_ORPHAN_SESSIONS).executeUpdate();
		int orphanRules = hibernate.session().createNativeQuery(DELETE + SQL_ORPHAN_RULES).executeUpdate();
		int orphanJobs = hibernate.session().createNativeQuery(DELETE + SQL_ORPHAN_JOBS).executeUpdate();
		int orphanDatasets = hibernate.session().createNativeQuery(DELETE + SQL_ORPHAN_DATASETS).executeUpdate();
		int orphanFiles = hibernate.session().createNativeQuery(DELETE + SQL_ORPHAN_FILES).executeUpdate();

		HashMap<String, Object> orphans = new HashMap<String, Object>() {
			{
				put("orphanFiles", orphanFiles);
				put("orphanDatasets", orphanDatasets);
				put("orphanJobs", orphanJobs);
				put("orphanRules", orphanRules);
				put("orphanSessions", orphanSessions);
			}
		};

		for (String key : orphans.keySet()) {
			logger.info("deleted orphans: " + key + " " + orphans.get(key));
		}

		return orphans;
	}

	@GET
	@Path("topics")
	@RolesAllowed({ Role.MONITORING, Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getClients(@Context SecurityContext sc) {

		return Response.ok(pubSubServer.getTopics()).build();
	}

	@GET
	@Path("users/quota")
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getQuota(@QueryParam("userId") List<String> userId, @Context SecurityContext sc) {
		
		List<String> userIdsToGet;
		if (userId == null || userId.size() == 0) {
			logger.info("get quotas for all users");
			userIdsToGet = ruleTable.getUsers();
			logger.info("got " + userIdsToGet.size() + " users");
		} else {
			logger.info("get quotas for " + userId);
			userIdsToGet = userId;
		}
		
		
		List<HashMap<String, Object>> results = new ArrayList<HashMap<String, Object>>();
		
		for (String uId: userIdsToGet) {
			HashMap<String, Object> singleUserQuotas = new HashMap<String, Object>();
			
			if (uId != null && !uId.equals("null")) {
				try {
			    	
			    	long size = ruleTable.getTotalSize(uId);
		
				    Long readWriteSessions = (Long) hibernate.session()
							.createQuery("select count(*) from Rule where username=:username and readWrite=true")
							.setParameter("username", uId).uniqueResult();
		
					Long readOnlySessions = (Long) hibernate.session()
							.createQuery("select count(*) from Rule where username=:username and readWrite=false")
							.setParameter("username", uId).uniqueResult();
		
						singleUserQuotas.put("userId", uId);
						singleUserQuotas.put("readWriteSessions", readWriteSessions);
						singleUserQuotas.put("readOnlySessions", readOnlySessions);
						singleUserQuotas.put("size", size);
					
			    } catch (Exception e) {
					logger.warn("failed to get quota for user " + uId, e);
					singleUserQuotas.put("userId", uId);
			    }
			} else {
				if (uId == null) {
					logger.warn("userId is null");
				} else {
					logger.warn("userId is 'null'");
				}
				singleUserQuotas.put("userId", uId);
			}
			results.add(singleUserQuotas);
		}

		return Response.ok(results).build();
	}

	@GET
	@Path(PATH_USERS_SESSIONS)
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getSessions(@NotNull @QueryParam("userId") List<String> userId, @Context SecurityContext sc) {

		List<HashMap<String, Object>> results = new ArrayList<HashMap<String, Object>>();
		
		for (String uId: userId) {
			List<Session> sessions = sessionDbApi.getSessions(uId);

			List<HashMap<String, Object>> sessionSizes = new ArrayList<>();

			for (Session session : sessions) {

				List<Dataset> datasets = SessionDbApi.getDatasets(hibernate.session(), session);
				List<Job> jobs = SessionDbApi.getJobs(hibernate.session(), session);

				long sessionSize = datasets.stream().map(dataset -> dataset.getFile()).filter(file -> file != null)
						.collect(Collectors.toMap(file -> file.getFileId(), file -> file)).values().stream()
						.collect(Collectors.summingLong(file -> file.getSize()));

				long datasetsCount = datasets.size();
				long jobCount = jobs.size();
				long inputCount = jobs.stream().flatMap(job -> job.getInputs().stream()).count();
				long parameterCount = jobs.stream().flatMap(job -> job.getParameters().stream()).count();
				long metadataCount = datasets.stream().flatMap(dataset -> dataset.getMetadataFiles().stream()).count();

				sessionSizes.add(new HashMap<String, Object>() {
					{
						put("sessionId", session.getSessionId());
						put("name", session.getName());
						put("size", sessionSize);
						put("datasetCount", datasetsCount);
						put("jobCount", jobCount);
						put("inputCount", inputCount);
						put("parameterCount", parameterCount);
						put("metadataFileCount", metadataCount);
					}
				});
			}

			HashMap<String, Object> singleResult = new HashMap<String, Object>();
			singleResult.put("userId", uId);
			singleResult.put("sessions", sessionSizes);
			
			
			results.add(singleResult);
		}
		return Response.ok(results).build();
	}

		
	/**
	 * Deletes rules for give user(s).
	 * 
	 * @param userId
	 * @param sc
	 * @return Deleted rules as json
	 */
	@DELETE
	@Path(PATH_USERS_SESSIONS)
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response deleteSessions(@NotNull @QueryParam("userId") List<String> userId, @Context SecurityContext sc) {
		logger.info("deleting sessions for " + userId);
		List<Rule> deletedRules = new ArrayList<Rule>();
		for (String uId: userId) {
			deletedRules.addAll(this.sessionDbApi.deleteRulesWithUser(uId));
		}
		
		logger.info("deleted " + deletedRules.size() + " rules");
		return Response.ok(deletedRules).build();
	}
	
	
	@POST
	@Path("news")
	@RolesAllowed({ Role.ADMIN })
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response post(News news, @Context UriInfo uriInfo, @Context SecurityContext sc) {

		// curl 'http://127.0.0.1:8104/admin/news' -H "Authorization: Basic $TOKEN" -H
		// 'Content-Type: application/json' -d '{"contents": "{}"}' -X POST

		// decide sessionId on the server
		if (news.getNewsId() != null) {
			throw new BadRequestException("session already has an id, post not allowed");
		}

		UUID id = RestUtils.createUUID();
		news.setNewsId(id);
		news.setCreated(Instant.now());
		
		this.newsApi.create(news);
		
		URI uri = uriInfo.getAbsolutePathBuilder().path(id.toString()).build();

		ObjectNode json = new JsonNodeFactory(false).objectNode();
		json.put("newsId", id.toString());
		
		return Response.created(uri).entity(json).build();
	}

	@PUT
	@Path("news/{id}")
	@RolesAllowed({ Role.ADMIN }) // don't allow Role.UNAUTHENTICATED
    @Consumes(MediaType.APPLICATION_JSON)
	@Transaction
    public Response put(News requestNews, @PathParam("id") UUID newsId, @Context SecurityContext sc) {
		
				    				
		// override the url in json with the id in the url, in case a 
		// malicious client has changed it
		requestNews.setNewsId(newsId);
		requestNews.setModified(Instant.now());
		
		// check the notification exists (is this needed?)
		News dbNotification = this.newsApi.getNews(newsId);
		
		requestNews.setCreated(dbNotification.getCreated());
		
		this.newsApi.update(requestNews);
				
		return Response.noContent().build();
    }	
	
	@DELETE
    @Path("news/{id}")
	@RolesAllowed({ Role.ADMIN })
	@Transaction
    public Response delete(@PathParam("id") UUID id, @Context SecurityContext sc) {

		this.newsApi.delete(id);
		
		return Response.noContent().build();
    }
}
