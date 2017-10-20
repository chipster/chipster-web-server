package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.scheduler.IdPair;
import fi.csc.microarray.messaging.JobState;

public class GlobalJobResourceTest {
	
	@SuppressWarnings("unused")
	private final Logger logger = LogManager.getLogger();

	private static TestServerLauncher launcher;

	private static SessionDbClient user1Client;
	private static SessionDbClient schedulerClient;

	private static UUID sessionId1;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
    	
    	user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token());
		schedulerClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getSchedulerToken());
		    	
		sessionId1 = user1Client.createSession(RestUtils.getRandomSession());
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }           

	@Test
    public void get() throws IOException, RestException {
        
		List<IdPair> oldJobs = schedulerClient.getJobs(JobState.NEW);
		
		UUID jobId = user1Client.createJob(sessionId1, RestUtils.getRandomJob());
		
		List<IdPair> newJobs = schedulerClient.getJobs(JobState.NEW);
		
		assertEquals(oldJobs.size() + 1, newJobs.size());
		assertEquals(false, oldJobs.stream().filter(job -> job.getJobId().equals(jobId)).findAny().isPresent());
		assertEquals(true, newJobs.stream().filter(job -> job.getJobId().equals(jobId)).findAny().isPresent());
		
		// normal user
		try {
			user1Client.getJobs(JobState.NEW);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(403, e.getResponse().getStatus());
    	}	
    }	
}
