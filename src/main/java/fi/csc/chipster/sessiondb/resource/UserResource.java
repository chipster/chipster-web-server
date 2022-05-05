package fi.csc.chipster.sessiondb.resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

@Path(UserResource.PATH_USERS)
public class UserResource {

	public static final String PATH_USERS = "users";

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;

	public UserResource(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}

	@GET
	@RolesAllowed({ Role.ADMIN, Role.FILE_STORAGE, Role.FILE_BROKER })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getAll(@Context SecurityContext sc) {

		@SuppressWarnings("unchecked")
		List<String> users = hibernate.session().createQuery("select distinct(username) from Rule").list();

		// everyone isn't a real user
		users.remove(RuleTable.EVERYONE);

		return Response.ok(users).build();
	}

	@GET
	@Path("{username}/quota")
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getQuota(@PathParam("username") String username, @Context SecurityContext sc) {

		@SuppressWarnings("unchecked")
		List<Rule> rules = hibernate.session().createQuery("from Rule where username=:username")
				.setParameter("username", username).list();

		List<Dataset> datasets = rules.stream().map(rule -> rule.getSession())
				.flatMap(session -> SessionDatasetResource.getDatasets(hibernate.session(), session).stream())
				.collect(Collectors.toList());

		// in case of duplicate IDs, pick any of them. They all should come from the
		// same DB row even if there are multiple Java objects
		Map<UUID, File> uniqueFiles = datasets.stream().map(dataset -> dataset.getFile()).filter(file -> file != null)
				.collect(Collectors.toMap(file -> file.getFileId(), file -> file, (f1, f2) -> f1));

		Long size = uniqueFiles.values().stream().collect(Collectors.summingLong(file -> file.getSize()));

		Long readWriteSessions = (Long) hibernate.session()
				.createQuery("select count(*) from Rule where username=:username and readWrite=true")
				.setParameter("username", username).uniqueResult();

		Long readOnlySessions = (Long) hibernate.session()
				.createQuery("select count(*) from Rule where username=:username and readWrite=false")
				.setParameter("username", username).uniqueResult();

		HashMap<String, Object> responseObj = new HashMap<String, Object>() {
			{
				put("username", username);
				put("readWriteSessions", readWriteSessions);
				put("readOnlySessions", readOnlySessions);
				put("size", size);
			}
		};

		return Response.ok(responseObj).build();
	}

	@GET
	@Path("{username}/sessions")
	@RolesAllowed({ Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getSessions(@PathParam("username") String username, @Context SecurityContext sc) {

		@SuppressWarnings("unchecked")
		List<Rule> rules = hibernate.session().createQuery("from Rule where username=:username")
				.setParameter("username", username).list();

		List<Session> sessions = rules.stream().map(rule -> rule.getSession()).collect(Collectors.toList());

		List<HashMap<String, Object>> sessionSizes = new ArrayList<>();

		for (Session session : sessions) {

			List<Dataset> datasets = SessionDatasetResource.getDatasets(hibernate.session(), session);
			List<Job> jobs = SessionJobResource.getJobs(hibernate.session(), session);

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

		return Response.ok(sessionSizes).build();
	}

}
