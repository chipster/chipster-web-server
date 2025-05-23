package fi.csc.chipster.sessiondb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;

public class SessionJobResourceTest {

	@SuppressWarnings("unused")
	private final Logger logger = LogManager.getLogger();

	private static TestServerLauncher launcher;

	private static SessionDbClient user1Client;
	private static SessionDbClient user2Client;
	private static SessionDbClient schedulerClient;

	private static UUID sessionId1;
	private static UUID sessionId2;

	@BeforeAll
	public static void setUp() throws Exception {
		Config config = new Config();
		launcher = new TestServerLauncher(config);

		user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		user2Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);
		schedulerClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getSchedulerToken(), Role.CLIENT);

		sessionId1 = user1Client.createSession(RestUtils.getRandomSession());
		sessionId2 = user2Client.createSession(RestUtils.getRandomSession());
	}

	@AfterAll
	public static void tearDown() throws Exception {
		launcher.stop();
	}

	@Test
	public void post() throws RestException {
		user1Client.createJob(sessionId1, RestUtils.getRandomJob());
	}

	public static void testCreateJob(int expected, UUID sessionId, Job job, SessionDbClient client) {
		try {
			client.createJob(sessionId, job);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	@Test
	public void postWithId() throws RestException {
		Job job = RestUtils.getRandomJob();
		job.setJobIdPair(sessionId1, RestUtils.createUUID());
		user1Client.createJob(sessionId1, job);
	}

	@Test
	public void postWrongUser() throws RestException {
		Job job = RestUtils.getRandomJob();
		job.setJobIdPair(sessionId2, RestUtils.createUUID());
		testCreateJob(403, sessionId2, job, user1Client);
	}

	@Test
	public void postWithWrongId() throws RestException {
		Job job = RestUtils.getRandomJob();
		job.setJobIdPair(sessionId2, RestUtils.createUUID());
		testCreateJob(400, sessionId1, job, user1Client);
	}

	@Test
	public void postWithSameJobId() throws RestException {
		Job job1 = RestUtils.getRandomJob();
		Job job2 = RestUtils.getRandomJob();
		UUID jobId = RestUtils.createUUID();
		job1.setJobIdPair(sessionId1, jobId);
		job2.setJobIdPair(sessionId2, jobId);
		String name1 = "name1";
		String name2 = "name2";
		job1.setToolId(name1);
		job2.setToolId(name2);

		user1Client.createJob(sessionId1, job1);
		user2Client.createJob(sessionId2, job2);

		// check that there are really to different jobs on the server
		assertEquals(name1, user1Client.getJob(sessionId1, jobId).getToolId());
		assertEquals(name2, user2Client.getJob(sessionId2, jobId).getToolId());
	}

	@Test
	public void get() throws IOException, RestException {

		UUID jobId = user1Client.createJob(sessionId1, RestUtils.getRandomJob());
		assertEquals(true, user1Client.getJob(sessionId1, jobId) != null);
		assertEquals(true, schedulerClient.getJob(sessionId1, jobId) != null);

		// wrong user
		testGetJob(403, sessionId1, jobId, user2Client);

		// wrong session
		testGetJob(403, sessionId2, jobId, user1Client);
		testGetJob(404, sessionId2, jobId, user2Client);
	}

	public static void testGetJob(int expected, UUID sessionId, UUID jobId, SessionDbClient client) {
		try {
			client.getJob(sessionId, jobId);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	@Test
	public void getIds() throws RestException {

		UUID id1 = user1Client.createJob(sessionId1, RestUtils.getRandomJob());
		UUID id2 = user1Client.createJob(sessionId1, RestUtils.getRandomJob());

		assertEquals(true, user1Client.getJobIds(sessionId1).contains(id1));
		assertEquals(true, user1Client.getJobIds(sessionId1).contains(id2));

		// wrong user

		testGetJobIds(403, sessionId1, user2Client);

		// wrong session
		assertEquals(false, user2Client.getJobIds(sessionId2).contains(id1));
	}

	@Test
	public void getJobsWithIds() throws RestException {

		UUID id1 = user1Client.createJob(sessionId1, RestUtils.getRandomJob());
		UUID id2 = user1Client.createJob(sessionId1, RestUtils.getRandomJob());
		ArrayList<UUID> idList = new ArrayList<UUID>() {
			{
				add(id1);
				add(UUID.randomUUID());
				add(id2);
			}
		};

		List<Job> jobs = user1Client.getJobs(sessionId1, idList);

		assertEquals(3, jobs.size());

		assertEquals(id1, jobs.removeFirst().getJobId());
		// job doesn't exist, should be null
		assertEquals(null, jobs.removeFirst());
		assertEquals(id2, jobs.removeFirst().getJobId());

		// existing jobId, but wrong session
		assertEquals(null, user2Client.getJobs(sessionId2, idList).get(0));

		// empty list
		assertEquals(true, user1Client.getJobs(sessionId1, new ArrayList<>()).isEmpty());

		// wrong user
		testGetJobsWithIds(403, sessionId1, idList, user2Client);
	}

	@Test
	public void getAll() throws RestException {

		UUID id1 = user1Client.createJob(sessionId1, RestUtils.getRandomJob());
		UUID id2 = user1Client.createJob(sessionId1, RestUtils.getRandomJob());

		assertEquals(true, user1Client.getJobs(sessionId1).containsKey(id1));
		assertEquals(true, user1Client.getJobs(sessionId1).containsKey(id2));

		// wrong user

		testGetJobs(403, sessionId1, user2Client);

		// wrong session
		assertEquals(false, user2Client.getJobs(sessionId2).containsKey(id1));
	}

	public static void testGetJobs(int expected, UUID sessionId, SessionDbClient client) {
		try {
			client.getJobs(sessionId);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	public static void testGetJobIds(int expected, UUID sessionId, SessionDbClient client) {
		try {
			client.getJobIds(sessionId);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	public static void testGetJobsWithIds(int expected, UUID sessionId, List<UUID> jobIds, SessionDbClient client) {
		try {
			client.getJobs(sessionId, jobIds);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	@Test
	public void put() throws RestException {

		Job job = RestUtils.getRandomJob();
		UUID jobId = user1Client.createJob(sessionId1, job);

		// client
		job.setToolName("new name");
		user1Client.updateJob(sessionId1, job);
		assertEquals("new name", user1Client.getJob(sessionId1, jobId).getToolName());

		// comp
		job.setToolName("new name2");
		user1Client.updateJob(sessionId1, job);
		assertEquals("new name2", schedulerClient.getJob(sessionId1, jobId).getToolName());

		// wrong user
		testUpdateJob(403, sessionId1, job, user2Client);

		// wrong session
		testUpdateJob(403, sessionId2, job, user1Client);
		testUpdateJob(404, sessionId2, job, user2Client);
	}

	@Test
	public void createJobWithInputs() throws RestException {

		Job job = RestUtils.getRandomJob();
		UUID datasetId = user1Client.createDataset(sessionId1, RestUtils.getRandomDataset());

		ArrayList<Input> i = new ArrayList<>();
		i.add(RestUtils.getRandomInput(datasetId));
		job.setInputs(i);

		UUID jobId = user1Client.createJob(sessionId1, job);

		Assertions.assertNotNull(jobId);
	}

	/**
	 * Creating a job isn't allowed if we don't have access rights to its inputs
	 * 
	 * @throws RestException
	 */
	@Test
	public void createJobWithWrongInputs() throws RestException {

		Job job = RestUtils.getRandomJob();
		UUID datasetId = user2Client.createDataset(sessionId2, RestUtils.getRandomDataset());

		ArrayList<Input> i = new ArrayList<>();
		i.add(RestUtils.getRandomInput(datasetId));
		job.setInputs(i);

		testCreateJob(403, sessionId1, job, user1Client);
	}

	/**
	 * Updating a job must be allowed (for example to cancel it) even if the inputs
	 * have been deleted
	 * 
	 * @throws RestException
	 */
	@Test
	public void updateJobWitMissingInputs() throws RestException {

		Job job = RestUtils.getRandomJob();
		UUID datasetId = user1Client.createDataset(sessionId1, RestUtils.getRandomDataset());

		ArrayList<Input> i = new ArrayList<>();
		i.add(RestUtils.getRandomInput(datasetId));
		job.setInputs(i);

		user1Client.createJob(sessionId1, job);

		user1Client.deleteDataset(sessionId1, datasetId);

		job.setState(JobState.CANCELLED);

		user1Client.updateJob(sessionId1, job);
	}

	/**
	 * Updating a job isn't allowed if we don't have access rights to its inputs
	 * 
	 * @throws RestException
	 */
	@Test
	public void updateJobWithWrongInputs() throws RestException {

		Job job = RestUtils.getRandomJob();
		user1Client.createJob(sessionId1, job);

		UUID datasetId = user2Client.createDataset(sessionId2, RestUtils.getRandomDataset());

		ArrayList<Input> i = new ArrayList<>();
		i.add(RestUtils.getRandomInput(datasetId));
		job.setInputs(i);

		testUpdateJob(403, sessionId1, job, user1Client);
	}

	public static void testUpdateJob(int expected, UUID sessionId, Job job, SessionDbClient client) {
		try {
			client.updateJob(sessionId, job);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}

	@Test
	public void delete() throws RestException, InterruptedException {

		UUID jobId = user1Client.createJob(sessionId1, RestUtils.getRandomJob());

		// wrong user
		testDeleteJob(403, sessionId1, jobId, user2Client);

		// wrong session
		testDeleteJob(403, sessionId2, jobId, user1Client);
		testDeleteJob(404, sessionId2, jobId, user2Client);

		// wait a minute so that scheduler can process the job creation event
		// otherwise it will complain in a log
		Thread.sleep(500);

		// delete
		user1Client.deleteJob(sessionId1, jobId);

		// doesn't exist anymore
		testDeleteJob(404, sessionId1, jobId, user1Client);
	}

	public static void testDeleteJob(int expected, UUID sessionId, UUID jobId, SessionDbClient client) {
		try {
			client.deleteJob(sessionId, jobId);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(expected, e.getResponse().getStatus());
		}
	}
}
