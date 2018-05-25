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
import javax.ws.rs.GET;
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

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;


public class JobHistoryResource extends AdminResource {
	@SuppressWarnings("unused")
	private Config config;
	private HibernateUtil hibernate;
	private Logger logger = LogManager.getLogger();
	public static final String FILTER_ATTRIBUTE_TIME = "Time";
	

	public JobHistoryResource(HibernateUtil hibernate,Config config) {
		super();
		this.config = config;
		this.hibernate = hibernate;
	}

	// http://localhost:8014/jobhistory?startTime=gt=2018-02-21T14:16:18.585Z
	@GET
	@Path("jobhistory")
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getJobHistory(@Context UriInfo uriInfo) {

		MultivaluedMap<String, String> queryParams = uriInfo
				.getQueryParameters();
		System.out.println("query param"+queryParams);
		Map<String, String> parameters = new HashMap<String, String>();

		for (String str : queryParams.keySet()) {
			parameters.put(str, queryParams.getFirst(str));
		}
		System.out.println(parameters);
		CriteriaBuilder builder = getHibernate().session().getCriteriaBuilder();
		// Create CriteriaQuery
		CriteriaQuery<JobHistoryModel> criteria = builder
				.createQuery(JobHistoryModel.class);
		// Specify criteria root
		Root<JobHistoryModel> root = criteria.from(JobHistoryModel.class);

		if (parameters.size() > 0) {
			List<Predicate> predicate = new ArrayList<Predicate>();

			for (String key : parameters.keySet()) {
				if (key != null) {
					if (parameters.get(key).contains("gt")) {
						// add greater than filter
						try {
							if (key.contains(FILTER_ATTRIBUTE_TIME)) {
								String time=parameters.get(key).split("=")[1];
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
			// Query itself
			criteria.select(root).where(predicate.toArray(new Predicate[] {}));
			Collection<JobHistoryModel> jobHistoryList = getHibernate()
					.session().createQuery(criteria).getResultList();
			System.out.println(jobHistoryList);
			return Response.ok(toJaxbList(jobHistoryList)).build();

		} else {
			// Returning simple job history list without any filter attribute
			criteria.select(root);
			Collection<JobHistoryModel> jobHistoryList = getHibernate()
					.session().createQuery(criteria).getResultList();
			return Response.ok(toJaxbList(jobHistoryList)).build();

		}

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

	private GenericEntity<Collection<JobHistoryModel>> toJaxbList(
			Collection<JobHistoryModel> result) {
		return new GenericEntity<Collection<JobHistoryModel>>(result) {
		};
	}

	private HibernateUtil getHibernate() {
		return hibernate;
	}

}
