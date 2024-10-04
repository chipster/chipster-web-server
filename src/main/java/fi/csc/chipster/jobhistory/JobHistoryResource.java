package fi.csc.chipster.jobhistory;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.TemporalType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.query.Query;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

public class JobHistoryResource extends AdminResource {
	private static final String PARAM_GREATER_THAN = ">";
	private static final String PARAM_LESS_THAN = "<";
	private static final String PARAM_NOT_EQUAL = "!";

	private static final String SQL_COUNT_USERS_BEGIN = "select count(distinct createdby) from jobhistory where starttime >= :starttime and starttime < :endtime";
	private static final String SQL_COUNT_JOBS_BEGIN = "select count(*) from jobhistory where starttime >= :starttime and starttime < :endtime";

	@SuppressWarnings("unused")
	private Config config;
	private HibernateUtil hibernate;
	private Logger logger = LogManager.getLogger();
	public static final String FILTER_ATTRIBUTE_TIME = "created";
	public static final String FILTER_ATTRIBUTE_PAGE = "page";

	public JobHistoryResource(HibernateUtil hibernate, Config config) {
		super();
		this.config = config;
		this.hibernate = hibernate;
	}

	// http://localhost:8014/jobhistory/?startTime=>2018-02-21T14:16:18.585Z
	@GET
	@Path("jobhistory")
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getJobHistory(@Context UriInfo uriInfo) {

		int pageNumber = 1;
		int pageSize = 200;

		MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();

		Map<String, ArrayList<String>> params = getModifiable(queryParams);

		if (params.get(FILTER_ATTRIBUTE_PAGE) != null) {
			pageNumber = Integer.parseInt(params.get(FILTER_ATTRIBUTE_PAGE).get(0));
		}

		params.remove(FILTER_ATTRIBUTE_PAGE);

		CriteriaBuilder builder = getHibernate().session().getCriteriaBuilder();
		// Create CriteriaQuery
		CriteriaQuery<JobHistory> criteria = builder.createQuery(JobHistory.class);

		// Specify criteria root
		Root<JobHistory> root = criteria.from(JobHistory.class);

		if (queryParams.size() > 0) {
			List<Predicate> predicate = createPredicate(params, root, builder);
			// Query itself
			criteria.select(root).where(predicate.toArray(new Predicate[] {}));
		}

		criteria.select(root);
		criteria.orderBy(builder.desc(root.get("created")));
		Query<JobHistory> query = getHibernate().session().createQuery(criteria);
		query.setFirstResult((pageNumber - 1) * pageSize);
		query.setMaxResults(pageSize);
		List<JobHistory> jobHistoryList = query.getResultList();

		return Response.ok(toJaxbList(jobHistoryList)).build();
	}

	@GET
	@Path("jobhistory/rowcount")
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getJobHistoryRowCount(@Context UriInfo uriInfo) {

		MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();

		Map<String, ArrayList<String>> params = getModifiable(queryParams);

		params.remove(FILTER_ATTRIBUTE_PAGE);

		if (params.size() > 0) {
			try {
			    CriteriaBuilder builder = getHibernate().session().getCriteriaBuilder();
			    CriteriaQuery<Long> criteria = builder.createQuery(Long.class);     
			    Root<JobHistory> root = criteria.from(JobHistory.class);			        
				List<Predicate> predicate = createPredicate(params, root, builder);
				
				criteria.select(builder.count(root));
				criteria.where(predicate.toArray(new Predicate[] {}));
				Query<Long> query = getHibernate().session().createQuery(criteria);
				Long count = query.getSingleResult();
				return Response.ok(count).build();
			} catch (Exception e) {
				logger.error("failed to get job history row count", e);
			}

		} else {
			// Returning simple job history list without any filter attribute
			Long count = getRowCount(JobHistory.class, getHibernate());
			return Response.ok(count).build();
		}
		return Response.ok().build();
	}

	@GET
	@Path("jobhistory/statistics")
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getJobHistoryStatistics(@Context UriInfo uriInfo) {
		// TODO figure out date time zones

		MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();

		// TODO what to do if year not there
		String startYear = queryParams.get("year").get(0);
		String endYear = String.valueOf(Integer.parseInt(startYear) + 1);
		Date startDate = Date.from(Instant.parse(startYear + "-01-01T00:00:00.000Z"));
		Date endDate = Date.from(Instant.parse(endYear + "-01-01T00:00:00.000Z"));

		String module = queryParams.get("module").get(0);

		List<String> ignoreUsers = queryParams.get("ignoreUsers");

		@SuppressWarnings("rawtypes")
		Query userCountQuery = getFilteredQuery(SQL_COUNT_USERS_BEGIN, startDate, endDate, module, ignoreUsers);
		logger.info(userCountQuery.getQueryString());
		BigInteger userCount = (BigInteger) userCountQuery.getSingleResult();
		logger.info(userCount);

		@SuppressWarnings("rawtypes")
		Query jobCountQuery = getFilteredQuery(SQL_COUNT_JOBS_BEGIN, startDate, endDate, module, ignoreUsers);
		logger.info(jobCountQuery.getQueryString());
		BigInteger jobCount = (BigInteger) jobCountQuery.getSingleResult();
		logger.info(jobCount);

		HashMap<String, Object> resultMap = new HashMap<String, Object>();
		resultMap.put("userCount", userCount);
		resultMap.put("jobCount", jobCount);
		return Response.ok(resultMap).build();
	}

	@SuppressWarnings("rawtypes")
	private Query getFilteredQuery(String select, Date startTime, Date endTime, String module,
			List<String> ignoreUsers) {
		String sql = select;

		boolean useModuleFilter = module != null && !module.equals("all") && !module.equals("");
		if (useModuleFilter) {
			sql += " and module like :module";
		}

		if (ignoreUsers != null) {
			sql += getUserFilterSql(ignoreUsers);
		}
		sql = sql + " ;";

		Query query = hibernate.session().createNativeQuery(sql, BigInteger.class);

		query = query.setParameter("starttime", startTime, TemporalType.TIMESTAMP).setParameter("endtime", endTime,
		        TemporalType.TIMESTAMP);

		if (useModuleFilter) {
			query = query.setParameter("module", module);
		}

		if (ignoreUsers != null) {
			for (int i = 0; i < ignoreUsers.size(); i++) {
				query = query.setParameter("ignoreuser" + i, ignoreUsers.get(i));
			}
		}
		return query;
	}

	private String getUserFilterSql(List<String> ignoreUsers) {
		String sql = "";
		if (ignoreUsers != null && ignoreUsers.size() > 0) {
			sql += " and createdby not in (select distinct createdby from jobhistory where ";
			for (int i = 0; i < ignoreUsers.size(); i++) {
				sql += "createdby like :ignoreuser" + i;
				if (i < ignoreUsers.size() - 1) {
					sql += " or ";
				}
			}
			sql += ")";
		}
		return sql;
	}

	private Map<String, ArrayList<String>> getModifiable(MultivaluedMap<String, String> inMap) {

		Map<String, ArrayList<String>> outMap = new HashMap<>();

		for (String key : inMap.keySet()) {
			ArrayList<String> values = new ArrayList<>();
			for (String value : inMap.get(key)) {
				values.add(value);
			}
			outMap.put(key, values);
		}

		return outMap;
	}

	// how to encode jobIdPair to url if this is needed at all
	// @GET
	// @RolesAllowed({ Role.ADMIN })
	// @Path("/jobhistory/{id}")
	// @Produces(MediaType.APPLICATION_JSON)
	// @Transaction
	// public Response getJob(@PathParam("id") UUID jobId) {
	// JobHistoryModel result = getHibernate().session().get(
	// JobHistoryModel.class, jobId);
	// return Response.ok(result).build();
	// }

	// FIXME only needed in tests, how to disable this in prod?
	@PUT
	@Path("jobhistory")
	@RolesAllowed({ Role.ADMIN })
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response post(JobHistory jobHistory) {

		HibernateUtil.persist(jobHistory, getHibernate().session());

		return Response.ok().build();
	}

	private List<Predicate> createPredicate(Map<String, ArrayList<String>> params, Root<JobHistory> root,
			CriteriaBuilder builder) {
		// the first parameter is the page number we are seeking record, so no
		// need to add in query
		List<Predicate> predicate = new ArrayList<Predicate>();

		for (String key : params.keySet()) {
			for (String httpValue : params.get(key)) {
				String stringValue = null;
				Instant instantValue = null;

				if (httpValue.startsWith(PARAM_GREATER_THAN) || httpValue.startsWith(PARAM_LESS_THAN)
						|| httpValue.startsWith(PARAM_NOT_EQUAL)) {

					stringValue = httpValue.substring(1);
				} else {
					stringValue = httpValue;
				}

				boolean isTime = FILTER_ATTRIBUTE_TIME.equals(key);

				if (isTime) {
					try {
						instantValue = Instant.parse(stringValue);
					} catch (IllegalArgumentException e) {
						logger.error("error in parsing job history parameter", e);
						throw new BadRequestException("unable to parse " + stringValue);
					}
				}

				if (httpValue.startsWith(PARAM_GREATER_THAN)) {
					if (isTime) {
						predicate.add(builder.greaterThan(root.get(key), instantValue));
					} else {
						predicate.add(builder.greaterThan(root.get(key), stringValue));
					}

				} else if (httpValue.startsWith(PARAM_LESS_THAN)) {
					if (isTime) {
						predicate.add(builder.lessThan(root.get(key), instantValue));
					} else {
						predicate.add(builder.lessThan(root.get(key), stringValue));
					}

				} else if (httpValue.startsWith(PARAM_NOT_EQUAL)) {
					if (isTime) {
						predicate.add(builder.notEqual(root.get(key), instantValue));
					} else {
						predicate.add(builder.notEqual(root.get(key), stringValue));
					}
				} else {
					if (isTime) {
						predicate.add(builder.equal(root.get(key), instantValue));
					} else {
						predicate.add(builder.equal(root.get(key), stringValue));
					}
				}
			}
		}

		return predicate;
	}

	private GenericEntity<Collection<JobHistory>> toJaxbList(Collection<JobHistory> result) {
		return new GenericEntity<Collection<JobHistory>>(result) {
		};
	}

	private HibernateUtil getHibernate() {
		return hibernate;
	}

}
