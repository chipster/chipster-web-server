package fi.csc.chipster.jobhistory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.security.RolesAllowed;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.query.Query;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;

public class JobHistoryResource extends AdminResource {
	private static final String PARAM_GREATER_THAN = ">";
	private static final String PARAM_LESS_THAN = "<";
	private static final String PARAM_NOT_EQUAL = "!";
	
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

		String pageParam = params.get(FILTER_ATTRIBUTE_PAGE).get(0);
		if (pageParam != null) {
			pageNumber = Integer.parseInt(pageParam);
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

		CriteriaBuilder builder = getHibernate().session().getCriteriaBuilder();
		CriteriaQuery<JobHistory> criteria = builder.createQuery(JobHistory.class);
		Root<JobHistory> root = criteria.from(JobHistory.class);

		if (params.size() > 0) {
			try {
				List<Predicate> predicate = createPredicate(params, root, builder);
				CriteriaQuery<Long> q = builder.createQuery(Long.class);
				q.select(builder.count(q.from(JobHistory.class)));
				getHibernate().session().createQuery(q);
				q.where(predicate.toArray(new Predicate[] {}));
				Long count = getHibernate().session().createQuery(q).getSingleResult();
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
				
				if (httpValue.startsWith(PARAM_GREATER_THAN) 
						|| httpValue.startsWith(PARAM_LESS_THAN) 
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
