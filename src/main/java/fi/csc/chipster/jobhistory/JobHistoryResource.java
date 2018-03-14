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
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path("jobhistory")
public class JobHistoryResource {

	private Config config;
	private String jobDetail;
	private HibernateUtil hibernate;
	public static final String FILTER_ATTRIBUTE_TIME = "Time";

	public JobHistoryResource(HibernateUtil hibernate, Config config) {
		this.config = config;
		this.hibernate = hibernate;
	}

	// http://localhost:8200/jobhistory?startTime=gt=2018-02-21T14:16:18.585Z
	@GET
	@RolesAllowed({Role.ADMIN})
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getJobHistory(@Context UriInfo uriInfo) {

		MultivaluedMap<String, String> queryParams = uriInfo
				.getQueryParameters();
		Map<String, String> parameters = new HashMap<String, String>();

		for (String str : queryParams.keySet()) {
			parameters.put(str, queryParams.getFirst(str));
		}
		System.out.println("parameters" + parameters);

		CriteriaBuilder builder = getHibernate().session().getCriteriaBuilder();
		// Create CriteriaQuery
		CriteriaQuery<JobHistoryModel> criteria = builder
				.createQuery(JobHistoryModel.class);
		// Specify criteria root
		Root<JobHistoryModel> root = criteria.from(JobHistoryModel.class);

		if (parameters.size() > 0) {
			// handle multiple filter attribute
			// Constructing the list of parameters
			List<Predicate> predicate = new ArrayList<Predicate>();

			for (String key : queryParams.keySet()) {
				if (key != null) {
					// Need to address gt and lt here
					if (parameters.get(key).contains("gt")) {
						// add greater than filter
						try {
							if (key.contains(FILTER_ATTRIBUTE_TIME)) {
								Instant timeVal = Instant.parse(parameters.get(
										key).split("=")[1]);
								System.out.println("time is"
										+ timeVal.toString());
								predicate.add(builder.greaterThan(
										root.get(key), timeVal));
							} else {
								predicate.add(builder.greaterThan(
										root.get(key), parameters.get(key)));
							}

						} catch (IllegalArgumentException e) {
							//
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
							//
						}
					} else {
						try {
							predicate.add(builder.equal(root.get(key),
									parameters.get(key)));
						} catch (IllegalArgumentException e) {
						}
					}
				}
			}// End of For loop

			// Query itself
			criteria.select(root).where(predicate.toArray(new Predicate[] {}));
			Collection<JobHistoryModel> jobHistoryList = getHibernate()
					.session().createQuery(criteria).getResultList();
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
	@RolesAllowed({Role.ADMIN})
	@Path("{id}")
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

	// http://127.0.0.1:8200
	// http://0.0.0.0:8200
}
