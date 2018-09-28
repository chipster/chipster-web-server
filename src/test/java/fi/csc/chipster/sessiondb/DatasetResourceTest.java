package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;

public class DatasetResourceTest {
	private static TestServerLauncher launcher;

	private static SessionDbClient user1Client;
	private static SessionDbClient user2Client;
	private static UUID sessionId1;
	private static UUID sessionId2;

	private static SessionDbClient fileBrokerClient;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
    	
		user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		user2Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);
		
		fileBrokerClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getFileBrokerToken(), Role.CLIENT);

		sessionId1 = user1Client.createSession(RestUtils.getRandomSession());
		sessionId2 = user2Client.createSession(RestUtils.getRandomSession());
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }           

	@Test
    public void post() throws RestException {
		user1Client.createDataset(sessionId1, RestUtils.getRandomDataset());
    }
	
	@Test
    public void postModification() throws RestException {
    	Dataset dataset1 = RestUtils.getRandomDataset();
    	dataset1.setDatasetId(null);
    	Dataset dataset2 = RestUtils.getRandomDataset();
    	dataset2.setDatasetId(null);
    	// check that properties of the existing File can't be modified
    	
    	File file1 = new File();
    	file1.setFileId(RestUtils.createUUID());
    	file1.setSize(1);
    	
    	File file2 = new File();
    	file2.setFileId(file1.getFileId());
    	file2.setSize(2);
    	
    	dataset1.setFile(file1);
    	
    	user1Client.createDataset(sessionId1, dataset1);
    	
    	dataset1.setFile(file2);
    	dataset2.setFile(file2);
    	
    	// client can't modify the file of the existing dataset
    	testUpdateDataset(403, sessionId1, dataset1, user1Client);
    	// or create a new dataset that would modify it
    	testCreateDataset(403, sessionId1, dataset2, user1Client);

    	// but the file broker can (to modify file sizes when the donwload is finished)
    	assertEquals(204, fileBrokerClient.updateDataset(sessionId1, dataset1).getStatus());
    }

	public static void testCreateDataset(int expected, UUID sessionId, Dataset dataset, SessionDbClient client) {
		try {
    		client.createDataset(sessionId, dataset);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}

	@Test
    public void get() throws IOException, RestException {
        
		UUID datasetId = user1Client.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(true, user1Client.getDataset(sessionId1, datasetId) != null);
		
        // wrong user
		testGetDataset(403, sessionId1, datasetId, user2Client);
        
        // wrong session
		testGetDataset(403, sessionId2, datasetId, user1Client);
		testGetDataset(403, sessionId2, datasetId, user2Client);	
    }
	
	public static void testGetDataset(int expected, UUID sessionId, UUID datasetId, SessionDbClient client) {
		try {
    		client.getDataset(sessionId, datasetId);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	@Test
    public void getAll() throws RestException {
		
		UUID id1 = user1Client.createDataset(sessionId1, RestUtils.getRandomDataset());
		UUID id2 = user1Client.createDataset(sessionId1, RestUtils.getRandomDataset());

        assertEquals(true, user1Client.getDatasets(sessionId1).containsKey(id1));
        assertEquals(true, user1Client.getDatasets(sessionId1).containsKey(id2));
        
        // wrong user
        
        testGetDatasets(403, sessionId1, user2Client);
        
        // wrong session
        assertEquals(false, user2Client.getDatasets(sessionId2).containsKey(id1));
    }
	
	public static void testGetDatasets(int expected, UUID sessionId, SessionDbClient client) {
		try {
    		client.getDatasets(sessionId);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	@Test
    public void put() throws RestException {
        
		Dataset dataset = RestUtils.getRandomDataset();
		UUID datasetId = user1Client.createDataset(sessionId1, dataset);
		
		dataset.setName("new name");
		user1Client.updateDataset(sessionId1, dataset);
		assertEquals("new name", user1Client.getDataset(sessionId1, datasetId).getName());
		
		// wrong user
		testUpdateDataset(403, sessionId1, dataset, user2Client);
        
        // wrong session
		testUpdateDataset(403, sessionId2, dataset, user1Client);
		testUpdateDataset(404, sessionId2, dataset, user2Client);
    }
	
	public static void testUpdateDataset(int expected, UUID sessionId, Dataset dataset, SessionDbClient client) {
		try {
    		client.updateDataset(sessionId, dataset);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}

	@Test
    public void delete() throws RestException {
        
		UUID datasetId = user1Client.createDataset(sessionId1, RestUtils.getRandomDataset());
		
		// wrong user
		testDeleteDataset(403, sessionId1, datasetId, user2Client);
		
		// wrong session
		testDeleteDataset(403, sessionId2, datasetId, user1Client);
		testDeleteDataset(404, sessionId2, datasetId, user2Client);
		
		// delete
		user1Client.deleteDataset(sessionId1, datasetId);
        
        // doesn't exist anymore
		testGetDataset(404, sessionId1, datasetId, user1Client);
    }
	
	public static void testDeleteDataset(int expected, UUID sessionId, UUID datasetId, SessionDbClient client) {
		try {
    		client.deleteDataset(sessionId, datasetId);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
}
