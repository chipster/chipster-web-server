package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Session;

public class SessionDbTokenTest {
	private static final String TEST_FILE_CONTENT = "test file content";

	private static TestServerLauncher launcher;

	private static SessionDbClient user1Client;
	private static SessionDbClient user2Client;
	
	private static UUID sessionId1;
	private static UUID sessionId2;

	private static UUID datasetId1;
	private static UUID datasetId2;

	private static RestFileBrokerClient fileBrokerClient1;

	private static RestFileBrokerClient fileBrokerClient2;

	private static UUID jobId;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
    	
		user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		user2Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);

		fileBrokerClient1 = new RestFileBrokerClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		fileBrokerClient2 = new RestFileBrokerClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);
		
		sessionId1 = user1Client.createSession(RestUtils.getRandomSession());
		sessionId2 = user2Client.createSession(RestUtils.getRandomSession());
		
		datasetId1 = user1Client.createDataset(sessionId1, RestUtils.getRandomDataset());
		datasetId2 = user2Client.createDataset(sessionId2, RestUtils.getRandomDataset());
					
		jobId = user1Client.createJob(sessionId1, RestUtils.getRandomJob());
		
		fileBrokerClient1.upload(sessionId1, datasetId1, RestUtils.toInputStream(TEST_FILE_CONTENT));				
		fileBrokerClient2.upload(sessionId2, datasetId2, RestUtils.toInputStream(TEST_FILE_CONTENT));
    }

	@AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }
	
	/**
	 * This is the main usage of DatasetTokens, test it separately
	 * 
	 * @throws RestException
	 * @throws IOException
	 */
	@Test
    public void datasetTokenGetFile() throws RestException, IOException {		
		String datasetToken = user1Client.createDatasetToken(sessionId1, datasetId1, null);

		RestFileBrokerClient fileBroker = new RestFileBrokerClient(
				launcher.getServiceLocator(), new StaticCredentials("token", datasetToken), Role.CLIENT);
		InputStream is = fileBroker.download(sessionId1, datasetId1);
		assertEquals(TEST_FILE_CONTENT, RestUtils.toString(is));		
    }

	@Test
    public void datasetTokenAllowed() throws RestException, IOException {		
		String datasetToken = user1Client.createDatasetToken(sessionId1, datasetId1, null);
		SessionDbClient tokenClient = new SessionDbClient(launcher.getServiceLocator(), new StaticCredentials("token", datasetToken), Role.CLIENT);
		
		assertEquals(datasetId1, tokenClient.getDataset(sessionId1, datasetId1).getDatasetId());				
    }
		
	@Test
    public void sessionTokenAllowed() throws RestException, IOException {		
		String sessionToken = user1Client.createSessionToken(sessionId1, null);
		SessionDbClient tokenClient = new SessionDbClient(launcher.getServiceLocator(), new StaticCredentials("token", sessionToken), Role.CLIENT);
		
		assertEquals(sessionId1, tokenClient.getSession(sessionId1).getSessionId());
		assertEquals(datasetId1, tokenClient.getDataset(sessionId1, datasetId1).getDatasetId());
		assertEquals(false, tokenClient.getDatasets(sessionId1).isEmpty());
		assertEquals(false, tokenClient.getJobs(sessionId1).isEmpty());
		// getJob() could be allowed, but hasn't been needed yet		
    }
	
	@Test
    public void datasetTokenWrongUser() throws RestException, IOException {
		try {
			user2Client.createDatasetToken(sessionId1, datasetId1, 1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}				
    }
	
	@Test
    public void sessionTokenWrongUser() throws RestException, IOException {
		try {
			user2Client.createSessionToken(sessionId1, 1l);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}				
    }
	
	@Test
    public void datasetTokenWrongSession() throws RestException, IOException {
		try {
			user1Client.createDatasetToken(sessionId2, datasetId1, 1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}				
    }
		
	@Test
    public void datasetTokenWrongDataset() throws RestException, IOException {
		try {
			user1Client.createDatasetToken(sessionId1, datasetId2, 1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(404, e.getResponse().getStatus());
		}				
    }
	
	@Test
    public void datasetTokenExpire() throws RestException, IOException, InterruptedException {
		String datasetToken = user1Client.createDatasetToken(sessionId1, datasetId1, 1);
		Thread.sleep(1000);
		try {
			RestFileBrokerClient fileBroker = new RestFileBrokerClient(
					launcher.getServiceLocator(), new StaticCredentials("token", datasetToken), Role.CLIENT);
			fileBroker.download(sessionId1, datasetId1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(401, e.getResponse().getStatus());
		}
    }
	
	@Test
    public void sessionTokenExpire() throws RestException, IOException, InterruptedException {
		String sessionToken = user1Client.createSessionToken(sessionId1, 1l);
		Thread.sleep(1000);
		try {
			RestFileBrokerClient fileBroker = new RestFileBrokerClient(
					launcher.getServiceLocator(), new StaticCredentials("token", sessionToken), Role.CLIENT);
			fileBroker.download(sessionId1, datasetId1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(401, e.getResponse().getStatus());
		}
    }
	
	@Test
    public void datasetTokenUpload() throws RestException, IOException, InterruptedException {
		UUID datasetId = user1Client.createDataset(sessionId1, RestUtils.getRandomDataset());
		String datasetToken = user1Client.createDatasetToken(sessionId1, datasetId1, 1);
		try {
			RestFileBrokerClient fileBroker = new RestFileBrokerClient(
					launcher.getServiceLocator(), new StaticCredentials("token", datasetToken), Role.CLIENT);
			// DatasetToken is for read-only operations
			fileBroker.upload(sessionId1, datasetId, RestUtils.toInputStream(TEST_FILE_CONTENT));
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
    }
	
	@Test
    public void datasetTokenProhibited() throws RestException, IOException, InterruptedException {
		String datasetToken = user1Client.createDatasetToken(sessionId1, datasetId1, 1);
		Dataset dataset = user1Client.getDataset(sessionId1, datasetId1);
		SessionDbClient tokenClient = new SessionDbClient(launcher.getServiceLocator(), new StaticCredentials("token", datasetToken), Role.CLIENT);
		
		// DatasetToken is for read-only operations
		
		try {
			tokenClient.createDataset(sessionId1, RestUtils.getRandomDataset());
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		try {
			tokenClient.updateDataset(sessionId1, dataset);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		try {		
			tokenClient.deleteDataset(sessionId1, datasetId1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		// DatasetToken must not work for jobs
		
		try {
			tokenClient.getJobs(sessionId1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(401, e.getResponse().getStatus());
		}
		
		try {
			tokenClient.getJob(sessionId1, jobId);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		try {
			tokenClient.createJob(sessionId1, RestUtils.getRandomJob());
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		try {
			tokenClient.updateJob(sessionId1, user1Client.getJob(sessionId1, jobId));
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		try {		
			tokenClient.deleteJob(sessionId1, jobId);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		// DatasetToken must not work for sessions
		try {
			tokenClient.createSession(new Session());
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}		
		
		try {
			tokenClient.getSession(sessionId1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(401, e.getResponse().getStatus());
		}
		
		try {
			tokenClient.getSessions();
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		try {
			tokenClient.updateSession(user1Client.getSession(sessionId1));
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		try {
			tokenClient.deleteSession(sessionId1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
    }
	
	@Test
    public void sessionTokenProhibited() throws RestException, IOException, InterruptedException {
		String sessionToken = user1Client.createSessionToken(sessionId1, 1l);
		SessionDbClient tokenClient = new SessionDbClient(launcher.getServiceLocator(), new StaticCredentials("token", sessionToken), Role.CLIENT);
		
		// SessionToken is for read-only operations
		
		// dataset changes
		
		try {
			tokenClient.createDataset(sessionId1, RestUtils.getRandomDataset());
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		try {
			tokenClient.updateDataset(sessionId1, user1Client.getDataset(sessionId1, datasetId1));
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		try {		
			tokenClient.deleteDataset(sessionId1, datasetId1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		// job changes
		
		try {
			tokenClient.createJob(sessionId1, RestUtils.getRandomJob());
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		try {
			tokenClient.updateJob(sessionId1, user1Client.getJob(sessionId1, jobId));
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		try {		
			tokenClient.deleteJob(sessionId1, jobId);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		// session changes
		
		try {
			tokenClient.createSession(new Session());
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}						
		
		try {
			tokenClient.updateSession(user1Client.getSession(sessionId1));
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		try {
			tokenClient.deleteSession(sessionId1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		// session list
		try {
			tokenClient.getSessions();
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
    }
}
