package fi.csc.chipster.sessiondb;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestMethods;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.websocket.PubSubEndpoint;
import fi.csc.chipster.rest.websocket.WebSocketClient;
import fi.csc.chipster.rest.websocket.WebSocketClosedException;
import fi.csc.chipster.rest.websocket.WebSocketErrorException;
import fi.csc.chipster.scheduler.IdPair;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.News;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionListStats;
import fi.csc.chipster.sessiondb.model.TableStats;
import fi.csc.chipster.sessiondb.resource.NewsResource;
import fi.csc.chipster.sessiondb.resource.SessionDatasetResource;
import fi.csc.chipster.sessiondb.resource.SessionResource;
import fi.csc.chipster.sessiondb.resource.UserResource;
import jakarta.websocket.MessageHandler.Whole;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

public class SessionDbClient {

	private static final String QUERY_PARAM_ALLOW_INTERNAL_ADDRESSES = "allowInternalAddresses";

	public interface SessionEventListener {
		void onEvent(SessionEvent e);
	}

	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private ServiceLocatorClient serviceLocator;
	private CredentialsProvider credentials;

	private WebSocketClient client;

	private String sessionDbUri;
	private String sessionDbEventsUri;

	/**
	 * @param serviceLocator
	 * @param credentials
	 * @param role           set to Role.CLIENT to use public addresses, anything
	 *                       else, e.g. Role.SERVER to use internal addresses
	 */
	public SessionDbClient(ServiceLocatorClient serviceLocator, CredentialsProvider credentials, String role) {
		this.serviceLocator = serviceLocator;
		this.credentials = credentials;

		String sessionDbUri;
		String eventsUri;

		if (Role.CLIENT.equals(role)) {
			// client doesn't have access to internal URIs
			sessionDbUri = serviceLocator.getPublicUri(Role.SESSION_DB);
			eventsUri = serviceLocator.getPublicUri(Role.SESSION_DB_EVENTS);
		} else {
			// prefer internal URI's between servers
			sessionDbUri = serviceLocator.getInternalService(Role.SESSION_DB).getUri();
			eventsUri = serviceLocator.getInternalService(Role.SESSION_DB_EVENTS).getUri();
		}

		init(sessionDbUri, eventsUri);
	}

	public SessionDbClient(String sessionDbUri, String sessionDbEventsUri, CredentialsProvider credentials) {
		this.credentials = credentials;
		init(sessionDbUri, sessionDbEventsUri);
	}

	private void init(String sessionDbUri, String sessionDbEventsUri) {
		this.sessionDbUri = sessionDbUri;
		this.sessionDbEventsUri = sessionDbEventsUri;
	}

	private WebTarget getSessionDbTarget() {
		return credentials != null ? AuthenticationClient
				.getClient(credentials.getUsername(), credentials.getPassword(), true).target(sessionDbUri)
				: AuthenticationClient.getClient().target(sessionDbUri); // for testing
	}

	// events

	public void subscribe(String topic, final SessionEventListener listener, String name) throws RestException {

		try {

			UriBuilder uriBuilder = UriBuilder.fromUri(sessionDbEventsUri).queryParam(PubSubEndpoint.TOPIC_KEY, topic);

			this.client = new WebSocketClient(uriBuilder.toString(), new Whole<String>() {

				@Override
				public void onMessage(String message) {
					listener.onEvent(RestUtils.parseJson(SessionEvent.class, message));
				}

			}, true, name, credentials);
		} catch (InterruptedException | WebSocketErrorException | WebSocketClosedException e) {
			throw new RestException("websocket error", e);
		}
		return;
	}

	public void close() throws IOException {
		if (this.client != null) {
			client.shutdown();
		}
	}

	// targets

	private WebTarget getSessionsTarget() {
		return getSessionDbTarget().path("sessions");
	}

	private WebTarget getSessionTarget(UUID sessionId) {
		return getSessionsTarget().path(sessionId.toString());
	}

	private WebTarget getStatsTarget() {
		return getSessionsTarget().path("stats");
	}

	private WebTarget getDatasetsTarget(UUID sessionId) {
		return getSessionTarget(sessionId).path("datasets");
	}

	private WebTarget getDatasetTarget(UUID sessionId, UUID datasetId) {
		return getDatasetsTarget(sessionId).path(datasetId.toString());
	}

	private WebTarget getJobsTarget(UUID sessionId) {
		return getSessionTarget(sessionId).path("jobs");
	}

	private WebTarget getJobTarget(UUID sessionId, UUID jobId) {
		return getJobsTarget(sessionId).path(jobId.toString());
	}

	private WebTarget getRulesTarget(UUID sessionId) {
		return getSessionTarget(sessionId).path("rules");
	}

	private WebTarget getDatasetTokenTarget() {
		return getSessionDbTarget().path("tokens");
	}

	private WebTarget getRuleTarget(UUID sessionId, UUID authorizationId) {
		return getRulesTarget(sessionId).path(authorizationId.toString());
	}

	private WebTarget getNewsTarget() {
		return getSessionDbTarget().path(NewsResource.PATH_NEWS);
	}

	private WebTarget getNewsTarget(UUID id) {
		return getNewsTarget().path(id.toString());
	}

	// sessions

	/**
	 * @return a list of session objects without datasets and jobs
	 */
	public HashMap<UUID, Session> getSessions() throws RestException {
		List<Session> sessionList = RestMethods.getList(getSessionsTarget(), Session.class);

		HashMap<UUID, Session> map = new HashMap<>();

		for (Session session : sessionList) {
			map.put(session.getSessionId(), session);
		}

		return map;
	}

	public List<Session> getSessions(String userIdString) throws RestException {
		WebTarget target = getSessionsTarget().queryParam(SessionResource.QUERY_PARAM_USER_ID, userIdString);

		return RestMethods.getList(target, Session.class);
	}

	/**
	 * @param sessionId
	 * @return get a session object
	 * @throws RestException
	 */
	public fi.csc.chipster.sessiondb.model.Session getSession(UUID sessionId) throws RestException {

		Session session = RestMethods.get(getSessionTarget(sessionId), Session.class);

		return session;
	}

	public SessionListStats getStats() throws RestException {

		return RestMethods.get(getStatsTarget(), SessionListStats.class);
	}

	/**
	 * Upload a session. The server assigns the id for the session. It must be
	 * null when this method is called, and the new id will be set to the
	 * session object.
	 * 
	 * @param session
	 * @return
	 * @throws RestException
	 */
	public UUID createSession(Session session) throws RestException {
		UUID id = RestMethods.post(getSessionsTarget(), session);
		session.setSessionId(id);
		return id;
	}

	public void updateSession(Session session) throws RestException {
		RestMethods.put(getSessionTarget(session.getSessionId()), session);
	}

	public void deleteSession(UUID sessionId) throws RestException {
		RestMethods.delete(getSessionTarget(sessionId));
	}

	// dataset

	public HashMap<UUID, Dataset> getDatasets(UUID sessionId) throws RestException {
		List<Dataset> datasetList = RestMethods.getList(getDatasetsTarget(sessionId), Dataset.class);

		HashMap<UUID, Dataset> datasetMap = new HashMap<>();

		for (Dataset dataset : datasetList) {
			datasetMap.put(dataset.getDatasetId(), dataset);
		}

		return datasetMap;
	}

	public Dataset getDataset(UUID sessionId, UUID datasetId) throws RestException {
		return getDataset(sessionId, datasetId, false);
	}

	/**
	 * Check that the user is authorized to access the requested dataset
	 * 
	 * @param sessionId
	 * @param datasetId
	 * @param requireReadWrite
	 * @return
	 * @throws RestException
	 */
	public Dataset getDataset(UUID sessionId, UUID datasetId, boolean requireReadWrite) throws RestException {
		WebTarget target = getDatasetTarget(sessionId, datasetId);
		if (requireReadWrite) {
			target = target.queryParam(SessionDatasetResource.QUERY_PARAM_READ_WRITE, requireReadWrite);
		}
		return RestMethods.get(target, Dataset.class);
	}

	/**
	 * Upload a dataset. The server assigns the id for the dataset. It must be
	 * null when this method is called, and the new id will be set to the
	 * dataset object.
	 * 
	 * @param sessionId
	 * @param dataset
	 * @return
	 * @throws RestException
	 */
	public UUID createDataset(UUID sessionId, Dataset dataset) throws RestException {
		UUID id = RestMethods.post(getDatasetsTarget(sessionId), dataset);
		dataset.setDatasetIdPair(sessionId, id);
		return id;
	}

	public Response updateDataset(UUID sessionId, Dataset dataset) throws RestException {
		return RestMethods.put(getDatasetTarget(sessionId, dataset.getDatasetId()), dataset);
	}

	public Response updateRule(UUID sessionId, Rule rule) throws RestException {
		return RestMethods.put(getRuleTarget(sessionId, rule.getRuleId()), rule);
	}

	public Response updateDatasets(UUID sessionId, List<Dataset> datasets) throws RestException {
		return RestMethods.put(getDatasetsTarget(sessionId).path(RestUtils.PATH_ARRAY), datasets);
	}

	public void deleteDataset(UUID sessionId, UUID datasetId) throws RestException {
		RestMethods.delete(getDatasetTarget(sessionId, datasetId));
	}

	// jobs

	public HashMap<UUID, Job> getJobs(UUID sessionId) throws RestException {
		List<Job> jobsList = RestMethods.getList(getJobsTarget(sessionId), Job.class);

		HashMap<UUID, Job> jobMap = new HashMap<>();

		for (Job job : jobsList) {
			jobMap.put(job.getJobId(), job);
		}
		return jobMap;
	}

	public Job getJob(UUID sessionId, UUID jobId) throws RestException {
		return RestMethods.get(getJobTarget(sessionId, jobId), Job.class);
	}

	public List<IdPair> getJobs(JobState state) throws RestException {
		return RestMethods.getList(getSessionDbTarget().path("jobs").queryParam("state", state.toString()),
				IdPair.class);
	}

	public String createSessionToken(UUID sessionId, Long validSeconds) throws RestException {
		return this.createSessionToken(sessionId, validSeconds, false);
	}

	public String createSessionToken(UUID sessionId, Long validSeconds, boolean allowInternalAddresses)
			throws RestException {
		WebTarget target = getDatasetTokenTarget()
				.path("sessions").path(sessionId.toString());

		if (validSeconds != null) {
			target = target.queryParam("valid", Instant.now().plus(Duration.ofSeconds(validSeconds)).toString());
		}

		if (allowInternalAddresses) {
			target = target.queryParam(QUERY_PARAM_ALLOW_INTERNAL_ADDRESSES, true);
		}

		String sessionToken = RestMethods.postWithObjectResponse(target, null, String.class);

		return sessionToken;
	}

	public String createDatasetToken(UUID sessionId, UUID datasetId, Integer validSeconds) throws RestException {
		WebTarget target = getDatasetTokenTarget()
				.path("sessions").path(sessionId.toString())
				.path("datasets").path(datasetId.toString());

		if (validSeconds != null) {
			target = target.queryParam("valid", Instant.now().plus(Duration.ofSeconds(validSeconds)).toString());
		}

		String datasetToken = RestMethods.postWithObjectResponse(target, null, String.class);

		return datasetToken;
	}

	/**
	 * Upload a job. The server assigns the id for the job. It must be
	 * null when this method is called, and the new id will be set to the
	 * job object.
	 * 
	 * @param sessionId
	 * @param job
	 * @return
	 * @throws RestException
	 */
	public UUID createJob(UUID sessionId, Job job) throws RestException {
		UUID id = RestMethods.post(getJobsTarget(sessionId), job);
		job.setJobIdPair(sessionId, id);
		return id;
	}

	public List<UUID> createJobs(UUID sessionId, List<Job> jobs)
			throws RestException, JsonParseException, JsonMappingException, IOException {

		String json = RestMethods.postWithObjectResponse(getJobsTarget(sessionId).path(RestUtils.PATH_ARRAY), jobs,
				String.class);
		@SuppressWarnings("unchecked")
		HashMap<String, Object> respObj = RestUtils.getObjectMapper(true).readValue(json, HashMap.class);
		@SuppressWarnings("unchecked")
		ArrayList<Map<String, String>> jobListJson = (ArrayList<Map<String, String>>) respObj.get("jobs");

		List<UUID> ids = jobListJson.stream()
				.map(o -> o.get("jobId"))
				.map(s -> UUID.fromString(s))
				.collect(Collectors.toList());

		return ids;
	}

	public void updateJob(UUID sessionId, Job job) throws RestException {
		RestMethods.put(getJobTarget(sessionId, job.getJobId()), job);
	}

	public void deleteJob(UUID sessionId, UUID jobId) throws RestException {
		RestMethods.delete(getJobTarget(sessionId, jobId));
	}

	public Rule getRule(UUID sessionId, UUID ruleId) throws RestException {
		return RestMethods.get(getRuleTarget(sessionId, ruleId), Rule.class);
	}

	public List<TableStats> getTableStats() throws RestException {
		List<TableStats> tables = RestMethods.getList(getSessionDbTarget().path("admin").path("tables"),
				TableStats.class);

		return tables;
	}

	public UUID createRule(UUID sessionId, String username, boolean readWrite) throws RestException {
		return RestMethods.post(getRulesTarget(sessionId), new Rule(username, readWrite));
	}

	public UUID createRule(UUID sessionId, Rule rule) throws RestException {
		UUID ruleId = RestMethods.post(getRulesTarget(sessionId), rule);
		rule.setRuleId(ruleId);
		return ruleId;
	}

	public void deleteRule(UUID sessionId, UUID ruleId) throws RestException {
		RestMethods.delete(getRulesTarget(sessionId).path(ruleId.toString()));
	}

	public List<Rule> getRules(UUID sessionId) throws RestException {
		return RestMethods.getList(getRulesTarget(sessionId), Rule.class);
	}

	public List<Session> getShares() throws RestException {
		return RestMethods.getList(getSessionsTarget().path(SessionResource.PATH_SHARES), Session.class);
	}

	public List<String> getUsers() throws RestException {
		return RestMethods.getList(getSessionDbTarget().path(UserResource.PATH_USERS), String.class);
	}

	public List<News> getNews() throws RestException {
		return RestMethods.getList(getSessionDbTarget().path(NewsResource.PATH_NEWS), News.class);
	}

	public void updateNews(News news) throws RestException {
		RestMethods.put(getNewsTarget(news.getNewsId()), news);
	}

	public News getNews(UUID newsId) throws RestException {
		return RestMethods.get(getNewsTarget(newsId), News.class);
	}
}
