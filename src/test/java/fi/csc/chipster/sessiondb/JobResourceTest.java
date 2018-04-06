package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;

public class JobResourceTest {
	
	@SuppressWarnings("unused")
	private final Logger logger = LogManager.getLogger();

	private static TestServerLauncher launcher;

	private static SessionDbClient user1Client;
	private static SessionDbClient user2Client;
	private static SessionDbClient compClient;

	private static UUID sessionId1;
	private static UUID sessionId2;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
    	
    	user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		user2Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);
		compClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getCompToken(), Role.CLIENT);
		    	
		sessionId1 = user1Client.createSession(RestUtils.getRandomSession());
		sessionId2 = user2Client.createSession(RestUtils.getRandomSession());
    }

    @AfterClass
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
    public void get() throws IOException, RestException {
        
		UUID jobId = user1Client.createJob(sessionId1, RestUtils.getRandomJob());
		assertEquals(true, user1Client.getJob(sessionId1, jobId) != null);
		assertEquals(true, compClient.getJob(sessionId1, jobId) != null);
		
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
		assertEquals("new name2", compClient.getJob(sessionId1, jobId).getToolName());

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
		
		Assert.assertNotNull(jobId);
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
