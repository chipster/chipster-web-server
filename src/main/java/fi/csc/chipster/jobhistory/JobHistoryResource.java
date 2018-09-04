package fi.csc.chipster.jobhistory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
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
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.rest.hibernate.Transaction;

public class JobHistoryResource extends AdminResource {
	@SuppressWarnings("unused")
	private Config config;
	private HibernateUtil hibernate;
	private Logger logger = LogManager.getLogger();
	public static final String FILTER_ATTRIBUTE_TIME = "Time";
	public static final String FILTER_ATTRIBUTE_PAGE = "page";

	public JobHistoryResource(HibernateUtil hibernate, Config config) {
		super();
		this.config = config;
		this.hibernate = hibernate;
	}

	// http://localhost:8014/jobhistory/?startTime=gt=2018-02-21T14:16:18.585Z
	@GET
	@Path("jobhistory")
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getJobHistory(@Context UriInfo uriInfo) {

		int pageNumber = 1;
		int pageSize = 200;

		MultivaluedMap<String, String> queryParams = uriInfo
				.getQueryParameters();
		Map<String, String> parameters = new HashMap<String, String>();

		for (String str : queryParams.keySet()) {
			parameters.put(str, queryParams.getFirst(str));
		}
		
		String pageParam = parameters.get(FILTER_ATTRIBUTE_PAGE);
		if (pageParam != null) {			
			pageNumber = Integer.parseInt(pageParam);
		}
		parameters.remove(FILTER_ATTRIBUTE_PAGE);
		System.out.println("page number is" + pageNumber);
		
		CriteriaBuilder builder = getHibernate().session().getCriteriaBuilder();
		// Create CriteriaQuery
		CriteriaQuery<JobHistoryModel> criteria = builder
				.createQuery(JobHistoryModel.class);

		// Specify criteria root
		Root<JobHistoryModel> root = criteria.from(JobHistoryModel.class);
		List<Predicate> predicate = createPredicate(parameters, root, builder);

		if (parameters.size() > 0) {
			// Query itself
			criteria.select(root).where(predicate.toArray(new Predicate[] {}));
			Query<JobHistoryModel> query = getHibernate().session()
					.createQuery(criteria);
			Collection<JobHistoryModel> jobHistoryList = query.getResultList();
			return Response.ok(toJaxbList(jobHistoryList)).build();

		} else {
			// Returning simple job history list without any filter attribute
			criteria.select(root);
			Query<JobHistoryModel> query = getHibernate().session()
					.createQuery(criteria);
			query.setFirstResult((pageNumber - 1) * pageSize);
			query.setMaxResults(pageSize);
			Collection<JobHistoryModel> jobHistoryList = query.getResultList();
			return Response.ok(toJaxbList(jobHistoryList)).build();

		}

	}

	@GET
	@Path("jobhistory/rowcount")
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getJobHistoryRowCount(@Context UriInfo uriInfo) {

		MultivaluedMap<String, String> queryParams = uriInfo
				.getQueryParameters();
		Map<String, String> parameters = new HashMap<String, String>();

		for (String str : queryParams.keySet()) {
			parameters.put(str, queryParams.getFirst(str));
		}

		parameters.remove(FILTER_ATTRIBUTE_PAGE);
		System.out.println(parameters);

		CriteriaBuilder builder = getHibernate().session().getCriteriaBuilder();
		CriteriaQuery<JobHistoryModel> criteria = builder
				.createQuery(JobHistoryModel.class);
		Root<JobHistoryModel> root = criteria.from(JobHistoryModel.class);
		List<Predicate> predicate = createPredicate(parameters, root, builder);

		if (parameters.size() > 0) {
			try {
				CriteriaQuery<Long> q = builder.createQuery(Long.class);
				q.select(builder.count(q.from(JobHistoryModel.class)));
				getHibernate().session().createQuery(q);
				q.where(predicate.toArray(new Predicate[] {}));
				Long count = getHibernate().session().createQuery(q)
						.getSingleResult();
				System.out.println(" the parametered row numbers are" + count);
				return Response.ok(count).build();
			} catch (Exception e) {
				System.out.println("exception" + e);
			}

		} else {
			// Returning simple job history list without any filter attribute
			Long count = getRowCount(JobHistoryModel.class, getHibernate());
			return Response.ok(count).build();
		}
		return Response.ok().build();
	}

	@GET
	@RolesAllowed({ Role.ADMIN })
	@Path("/jobhistory/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getJob(@PathParam("id") UUID jobId) {
		JobHistoryModel result = getHibernate().session().get(
				JobHistoryModel.class, jobId);
		return Response.ok(result).build();
	}

	@PUT
	@Path("jobhistory")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response post(JobHistoryModel jobHistory) {

		getHibernate().runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(org.hibernate.Session hibernateSession) {
				hibernateSession.save(jobHistory);
				return null;
			}
		});
		return Response.ok().build();
	}

	private List<Predicate> createPredicate(Map<String, String> parameters,
			Root<JobHistoryModel> root, CriteriaBuilder builder) {
		// the first parameter is the page number we are seeking record, so no
		// need to add in query
		List<Predicate> predicate = new ArrayList<Predicate>();
		if (parameters.size() > 0) {
			for (String key : parameters.keySet()) {
				if (key != null) {
					if (parameters.get(key).contains("gt")) {
						// add greater than filter
						try {
							if (key.contains(FILTER_ATTRIBUTE_TIME)) {
								String time = parameters.get(key).split("=")[1];
								Instant timeVal = Instant.parse(time);
								predicate.add(builder.greaterThan(
										root.get(key), timeVal));
							} else {
								predicate.add(builder.greaterThan(
										root.get(key), parameters.get(key)));
							}

						} catch (IllegalArgumentException e) {
							logger.error(
									"error in parsing job history parameter", e);
						}

					} else if (parameters.get(key).contains("lt")) {
						// add lt filter
						try {
							if (key.contains(FILTER_ATTRIBUTE_TIME)) {
								Instant timeVal = Instant.parse(parameters.get(
										key).split("=")[1]);
								predicate.add(builder.lessThan(root.get(key),
										timeVal));
							} else {
								predicate.add(builder.lessThan(root.get(key),
										parameters.get(key)));
							}
						} catch (IllegalArgumentException e) {
							logger.error(
									"error in parsing job history parameter", e);
						}
					} else {
						try {
							predicate.add(builder.equal(root.get(key),
									parameters.get(key)));
						} catch (IllegalArgumentException e) {
							logger.error(
									"error in parsing job history parameter", e);
						}
					}
				}
			}
		}

		return predicate;
	}

	private GenericEntity<Collection<JobHistoryModel>> toJaxbList(
			Collection<JobHistoryModel> result) {
		return new GenericEntity<Collection<JobHistoryModel>>(result) {
		};
	}

	private HibernateUtil getHibernate() {
		return hibernate;
	}

}
