package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Dataset;

public class DatasetTokenTest {
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

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
    	
		user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token());
		user2Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token());

		fileBrokerClient1 = new RestFileBrokerClient(launcher.getServiceLocator(), launcher.getUser1Token());
		fileBrokerClient2 = new RestFileBrokerClient(launcher.getServiceLocator(), launcher.getUser2Token());
		
		sessionId1 = user1Client.createSession(RestUtils.getRandomSession());
		sessionId2 = user2Client.createSession(RestUtils.getRandomSession());
		
		datasetId1 = user1Client.createDataset(sessionId1, RestUtils.getRandomDataset());
		datasetId2 = user2Client.createDataset(sessionId2, RestUtils.getRandomDataset());
					
		fileBrokerClient1.upload(sessionId1, datasetId1, IOUtils.toInputStream(TEST_FILE_CONTENT));				
		fileBrokerClient2.upload(sessionId2, datasetId2, IOUtils.toInputStream(TEST_FILE_CONTENT));
    }

	@AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }           

	@Test
    public void post() throws RestException, IOException {		
		UUID datasetToken = user1Client.createDatasetToken(sessionId1, datasetId1, null);
		try {
			RestFileBrokerClient fileBroker = new RestFileBrokerClient(launcher.getServiceLocator(), new StaticCredentials("token", datasetToken.toString()));
			InputStream is = fileBroker.download(sessionId1, datasetId1);
			assertEquals(TEST_FILE_CONTENT, IOUtils.toString(is));
		} catch (RestException e) {
			assertEquals(true, false);
		}		
    }
	
	@Test
    public void postValid() throws RestException, IOException {		
		UUID datasetToken = user1Client.createDatasetToken(sessionId1, datasetId1, 1);
		try {
			RestFileBrokerClient fileBroker = new RestFileBrokerClient(launcher.getServiceLocator(), new StaticCredentials("token", datasetToken.toString()));
			InputStream is = fileBroker.download(sessionId1, datasetId1);
			assertEquals(TEST_FILE_CONTENT, IOUtils.toString(is));
		} catch (RestException e) {
			assertEquals(true, false);
		}		
    }
	
	@Test
    public void wrongUser() throws RestException, IOException {
		try {
			user2Client.createDatasetToken(sessionId1, datasetId1, 1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}				
    }
	
	@Test
    public void wrongSession() throws RestException, IOException {
		try {
			user1Client.createDatasetToken(sessionId2, datasetId1, 1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}				
    }
	
	@Test
    public void wrongDataset() throws RestException, IOException {
		try {
			user1Client.createDatasetToken(sessionId1, datasetId2, 1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(404, e.getResponse().getStatus());
		}				
    }
	
	@Test
    public void expire() throws RestException, IOException, InterruptedException {
		UUID datasetToken = user1Client.createDatasetToken(sessionId1, datasetId1, 1);
		Thread.sleep(1000);
		try {
			RestFileBrokerClient fileBroker = new RestFileBrokerClient(launcher.getServiceLocator(), new StaticCredentials("token", datasetToken.toString()));
			fileBroker.download(sessionId1, datasetId1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
    }
	
	@Test
    public void upload() throws RestException, IOException, InterruptedException {
		UUID datasetId = user1Client.createDataset(sessionId1, RestUtils.getRandomDataset());
		UUID datasetToken = user1Client.createDatasetToken(sessionId1, datasetId1, 1);
		try {
			RestFileBrokerClient fileBroker = new RestFileBrokerClient(launcher.getServiceLocator(), new StaticCredentials("token", datasetToken.toString()));
			// DatasetToken is for read-only operations
			fileBroker.upload(sessionId1, datasetId, IOUtils.toInputStream(TEST_FILE_CONTENT));
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
    }
	
	@Test
    public void updateDataset() throws RestException, IOException, InterruptedException {
		UUID datasetToken = user1Client.createDatasetToken(sessionId1, datasetId1, 1);
		Dataset dataset = user1Client.getDataset(sessionId1, datasetId1);
		SessionDbClient sessionDb = new SessionDbClient(launcher.getServiceLocator(), new StaticCredentials("token", datasetToken.toString()));
		try {
			// DatasetToken is for read-only operations
			sessionDb.updateDataset(sessionId1, dataset);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
    }
	
	@Test
    public void deleteDataset() throws RestException, IOException, InterruptedException {
		UUID datasetToken = user1Client.createDatasetToken(sessionId1, datasetId1, 1);
		UUID datasetId = user1Client.createDataset(sessionId1, RestUtils.getRandomDataset());
		SessionDbClient sessionDb = new SessionDbClient(launcher.getServiceLocator(), new StaticCredentials("token", datasetToken.toString()));
		try {
			// DatasetToken is for read-only operations
			sessionDb.deleteDataset(sessionId1, datasetId);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
    }
	
	@Test
    public void getSession() throws RestException, IOException, InterruptedException {
		UUID datasetToken = user1Client.createDatasetToken(sessionId1, datasetId1, 1);
		SessionDbClient sessionDb = new SessionDbClient(launcher.getServiceLocator(), new StaticCredentials("token", datasetToken.toString()));
		try {
			// DatasetToken must not work for sessions
			sessionDb.getSession(sessionId1);
			assertEquals(true, false);
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
    }
}
