package fi.csc.chipster.sessiondb;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import javax.websocket.MessageHandler.Whole;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.websocket.WebSocketClient;
import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketClosedException;
import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketErrorException;
import fi.csc.chipster.scheduler.IdPair;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.DatasetToken;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.TableStats;
import fi.csc.chipster.sessiondb.resource.SessionDatasetResource;
import fi.csc.microarray.exception.MicroarrayException;
import fi.csc.microarray.messaging.JobState;

public class SessionDbClient {
	
	public interface SessionEventListener {
		void onEvent(SessionEvent e);
	}
	
	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private ServiceLocatorClient serviceLocator;
	private List<String> sessionDbList;
	private List<String> sessionDbEventsList;
	private CredentialsProvider credentials;

	private WebSocketClient client;

	private String sessionDbUri;
	private String sessionDbEventsUri;

	public SessionDbClient(ServiceLocatorClient serviceLocator, CredentialsProvider credentials) {
		this.serviceLocator = serviceLocator;
		this.credentials = credentials;
		
		this.sessionDbList = serviceLocator.get(Role.SESSION_DB);
		this.sessionDbEventsList = serviceLocator.get(Role.SESSION_DB_EVENTS);
	
		if (sessionDbList.isEmpty()) {
			throw new InternalServerErrorException("no session-dbs registered to service-locator");
		}
		if (sessionDbEventsList.isEmpty()) {
			throw new InternalServerErrorException("no session-db-events registered to service-locator");
		}
		
		// just take the first one for now
		init(sessionDbList.get(0), sessionDbEventsList.get(0));
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
		return credentials != null ? AuthenticationClient.getClient(credentials.getUsername(), credentials.getPassword(), true).target(sessionDbUri) :
			AuthenticationClient.getClient().target(sessionDbUri); // for testing
	}
	
	
	// events

	public void subscribe(String topic, final SessionEventListener listener, String name) throws RestException {
		
		try {
			
			UriBuilder uriBuilder = UriBuilder.fromUri(sessionDbEventsUri).path(SessionDb.EVENTS_PATH).path(topic);
						
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
		return getSessionDbTarget().path("datasettokens");
	}	
	
	private WebTarget getRuleTarget(UUID sessionId, UUID authorizationId) {
		return getRulesTarget(sessionId).path(authorizationId.toString());
	}
	
	// methods 
	
	@SuppressWarnings("unchecked")
	private <T> List<T> getList(WebTarget target, Class<T> type) throws RestException {
		Response response = target.request().get(Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("get a list of " + type.getSimpleName() + " failed ", response, target.getUri());
		}
		String json = response.readEntity(String.class);
		return RestUtils.parseJson(List.class, type, json);
	}
	
	private <T> T get(WebTarget target, Class<T> type) throws RestException {
		Response response = target.request().get(Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("get " + type.getSimpleName() + " failed ", response, target.getUri());
		}		
		return response.readEntity(type);
	}
	
	private UUID post(WebTarget target, Object obj) throws RestException {
		Response response = target.request().post(Entity.entity(obj, MediaType.APPLICATION_JSON), Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("post " + obj.getClass().getSimpleName() + " failed ", response, target.getUri());
		}
		return UUID.fromString(RestUtils.basename(response.getLocation().getPath()));		
	}
	
	private <T> T postWithObjectResponse(WebTarget target, Object obj, Class<T> responseType) throws RestException {
		Entity<Object> entity = null;
		if (obj != null) {
			entity = Entity.entity(obj, MediaType.APPLICATION_JSON);
		} else {
			entity = Entity.json("");
		}
		Response response = target.request().post(entity, Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("post " + (obj == null ? null : obj.getClass().getSimpleName()) + " failed ", response, target.getUri());
		}
		
		return response.readEntity(responseType);		
	}
	
	private Response put(WebTarget target, Object obj) throws RestException {
		Response response = target.request().put(Entity.entity(obj, MediaType.APPLICATION_JSON), Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("put " + obj.getClass().getSimpleName() + " failed ", response, target.getUri());
		}
		return response;
	}
	
	private void delete(WebTarget target) throws RestException {
		Response response = target.request().delete(Response.class);
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw new RestException("delete failed ", response, target.getUri());
		}
	}
	
	// sessions
	
	/**
	 * @return a list of session objects without datasets and jobs
	 * @throws MicroarrayException
	 */
	public HashMap<UUID, Session> getSessions() throws RestException {		
		List<Session> sessionList = getList(getSessionsTarget(), Session.class);
		
		HashMap<UUID, Session> map = new HashMap<>();
		
		for (Session session : sessionList) {
			map.put(session.getSessionId(), session);
		}
		
		return map;
	}

	/**
	 * @param sessionId
	 * @return get a session object and it's datasets and jobs
	 * @throws RestException
	 */
	public fi.csc.chipster.sessiondb.model.Session getSession(UUID sessionId) throws RestException {
		
		Session session = get(getSessionTarget(sessionId), Session.class);
		
		HashMap<UUID, Dataset> datasets = getDatasets(sessionId);
		HashMap<UUID, Job> jobs = getJobs(sessionId);
		
		session.setDatasets(datasets);
		session.setJobs(jobs);
		
        return session;
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
		UUID id =  post(getSessionsTarget(), session);
		session.setSessionId(id);
		return id;
	}

	public void updateSession(Session session) throws RestException {
		put(getSessionTarget(session.getSessionId()), session);
	}

	public void deleteSession(UUID sessionId) throws RestException {
		delete(getSessionTarget(sessionId));
	}	
	
	// dataset
	
	public HashMap<UUID, Dataset> getDatasets(UUID sessionId) throws RestException {
		List<Dataset> datasetList = getList(getDatasetsTarget(sessionId), Dataset.class);
		
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
			target.queryParam(SessionDatasetResource.QUERY_PARAM_READ_WRITE, requireReadWrite);
		}
		return get(target, Dataset.class);		
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
		UUID id = post(getDatasetsTarget(sessionId), dataset);
		dataset.setDatasetId(id);
		return id;
	}
	
	public Response updateDataset(UUID sessionId, Dataset dataset) throws RestException {
		return put(getDatasetTarget(sessionId, dataset.getDatasetId()), dataset);
	}
	
	public void deleteDataset(UUID sessionId, UUID datasetId) throws RestException {
		delete(getDatasetTarget(sessionId, datasetId));
	}	
	
	// jobs
	
	public HashMap<UUID, Job> getJobs(UUID sessionId) throws RestException {
		List<Job> jobsList = getList(getJobsTarget(sessionId), Job.class);
		
		HashMap<UUID, Job> jobMap = new HashMap<>();
		
		for (Job job : jobsList) {
			jobMap.put(job.getJobId(), job);
		}
		return jobMap;
	}
	
	public Job getJob(UUID sessionId, UUID jobId) throws RestException {
		return get(getJobTarget(sessionId, jobId), Job.class);	
	}
	
	public List<IdPair> getJobs(JobState state) throws RestException {
		return getList(getSessionDbTarget().path("jobs").queryParam("state", state.toString()), IdPair.class);
	}
	
	public UUID createDatasetToken(UUID sessionId, UUID datasetId, Integer validSeconds) throws RestException {
		WebTarget target = getDatasetTokenTarget()
		.path("sessions").path(sessionId.toString())
		.path("datasets").path(datasetId.toString());
		
		if (validSeconds != null) {
			target = target.queryParam("valid", LocalDateTime.now().plus(Duration.ofSeconds(validSeconds)).toString());
		}
		
		DatasetToken datasetToken = postWithObjectResponse(target, null, DatasetToken.class);
		
		return datasetToken.getTokenKey();
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
		UUID id = post(getJobsTarget(sessionId), job);
		job.setJobId(id);
		return id;
	}
	
	public void updateJob(UUID sessionId, Job job) throws RestException {
		put(getJobTarget(sessionId, job.getJobId()), job);
	}
	
	public void deleteJob(UUID sessionId, UUID jobId) throws RestException {
		delete(getJobTarget(sessionId, jobId));
	}
	
	public Rule getRule(UUID sessionId, UUID ruleId) throws RestException {
		return get(getRuleTarget(sessionId, ruleId), Rule.class);
	}

	public List<TableStats> getTableStats() throws RestException {
		List<TableStats> tables = getList(getSessionDbTarget().path("admin").path("tables"), TableStats.class);
		
		return tables;
	}
	
	public UUID createRule(UUID sessionId, String username, boolean readWrite) throws RestException {
		return post(getRulesTarget(sessionId), new Rule(username, readWrite));
	}

	public UUID createRule(UUID sessionId, Rule rule) throws RestException {
		return post(getRulesTarget(sessionId), rule);
	}
	
	public void deleteRule(UUID sessionId, UUID ruleId) throws RestException {
		delete(getRulesTarget(sessionId).path(ruleId.toString()));
	}
	
	public List<Rule> getRules(UUID sessionId) throws RestException {
		return getList(getRulesTarget(sessionId), Rule.class);
	}
}
