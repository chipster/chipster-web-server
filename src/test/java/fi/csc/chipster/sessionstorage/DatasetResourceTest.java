package fi.csc.chipster.sessionstorage;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServerLauncher;
import fi.csc.chipster.sessionstorage.model.Dataset;

public class DatasetResourceTest {

    private static final String DATASETS = "/datasets";
    private WebTarget user1Target;
    private WebTarget user2Target;
	private String session1Path;
	private String session2Path;
	private ServerLauncher server;
	private String datasets1Path;
	private String datasets2Path;

    @Before
    public void setUp() throws Exception {
    	server = new ServerLauncher(new SessionStorage(), SessionStorage.BASE_URI);
        server.startServersIfNecessary();
        user1Target = server.getUser1Target();
        user2Target = server.getUser2Target();
        
        session1Path = SessionResourceTest.postRandomSession(user1Target);
        session2Path = SessionResourceTest.postRandomSession(user2Target);
        datasets1Path = session1Path + DATASETS;
        datasets2Path = session2Path + DATASETS;
    }

    @After
    public void tearDown() throws Exception {
    	server.stop();
    }           

	@Test
    public void post() {
    	postRandomDataset(user1Target, session1Path);
    }
	
	@Test
    public void postModification() {
    	Dataset dataset1 = RestUtils.getRandomDataset();
    	dataset1.setDatasetId(null);
    	Dataset dataset2 = RestUtils.getRandomDataset();
    	dataset2.setDatasetId(null);
    	// check that properties of the existing File can't be modified
    	dataset2.getFile().setFileId(dataset1.getFile().getFileId());
    	dataset2.getFile().setSize(101);
    	assertEquals(201, post(user1Target, session1Path + DATASETS, dataset1).getStatus());
    	assertEquals(403, post(user1Target, session1Path + DATASETS, dataset2).getStatus());
    }

	//@Test
    public void postAndGetMany() throws JsonGenerationException, JsonMappingException, IOException {
        
		String objPath = null;
		for (int i = 0; i < 100; i++) {			
			objPath = postRandomDataset(user1Target, datasets1Path);		
		}
		for (int i = 0; i < 1000; i++) {
			assertEquals(false, getDataset(user1Target, objPath) == null);
		}
    }

	@Test
    public void get() throws JsonGenerationException, JsonMappingException, IOException {
        
		String objPath = postRandomDataset(user1Target, session1Path);        
        assertEquals(false, getDataset(user1Target, objPath) == null);
        
        // wrong user
        assertEquals(404, get(user2Target, objPath));
        
        // wrong session
        assertEquals(404, get(user1Target, changeSession(objPath)));
        assertEquals(404, get(user2Target, changeSession(objPath)));
    }
	
	@Test
    public void getAll() {
        
		String id1 = RestUtils.basename(postRandomDataset(user1Target, session1Path));
		String id2 = RestUtils.basename(postRandomDataset(user1Target, session1Path));
		
		String json = getString(user1Target, datasets1Path);
        assertEquals(false, json == null);
        
        //TODO parse json
        assertEquals(true, json.contains(id1));
        assertEquals(true, json.contains(id2));
        
        // wrong user
        assertEquals(404, get(user2Target, datasets1Path));
        
        // wrong session
        String session2Json = getString(user2Target, datasets2Path);
        //TODO parse json
        assertEquals(false, session2Json.contains(id1));
    }
	
	@Test
    public void put() {
        
		String objPath = postRandomDataset(user1Target, session1Path);
		Dataset newObj = RestUtils.getRandomDataset();
        assertEquals(204, put(user1Target, objPath, newObj));
        
        // wrong user
        assertEquals(404, put(user2Target, objPath, newObj));
        
        // wrong session
        assertEquals(404, put(user1Target, changeSession(objPath), newObj));
        assertEquals(404, put(user2Target, changeSession(objPath), newObj));
    }

	@Test
    public void delete() {
        
		String objPath = postRandomDataset(user1Target, session1Path);
		
		// wrong user
		assertEquals(404, delete(user2Target, objPath));
		
		// wrong session
		assertEquals(404, delete(user1Target, changeSession(objPath)));
		assertEquals(404, delete(user2Target, changeSession(objPath)));
		
		// delete
        assertEquals(204, delete(user1Target, objPath));
        
        // doesn't exist anymore
        assertEquals(404, delete(user1Target, objPath));
    }
	
	private String changeSession(String objPath) {
		String session1Id = RestUtils.basename(session1Path);
        String session2Id = RestUtils.basename(session2Path);
        
        return objPath.replace(session1Id, session2Id);
	}
	
    public static String postRandomDataset(WebTarget target, String sessionPath) {
    	Dataset dataset = RestUtils.getRandomDataset();
    	dataset.setDatasetId(null);
    	Response response = post(target, sessionPath + DATASETS, dataset);
        assertEquals(201, response.getStatus());
        
        return sessionPath + DATASETS + "/" + RestUtils.basename(response.getLocation().getPath());
	}
	
	public static int delete(WebTarget target, String path) {
		return target.path(path).request().delete(Response.class).getStatus();
	}
	
    public static Response post(WebTarget target, String path, Dataset dataset) {
    	return target.path(path).request(MediaType.APPLICATION_JSON_TYPE).post(Entity.entity(dataset, MediaType.APPLICATION_JSON_TYPE),Response.class);
	}
    
	public static int put(WebTarget target, String path, Dataset dataset) {
		return target.path(path).request(MediaType.APPLICATION_JSON_TYPE).put(Entity.entity(dataset,  MediaType.APPLICATION_JSON_TYPE), Response.class).getStatus();
	}
	
	public static Dataset getDataset(WebTarget target, String path) {
		return target.path(path).request().get(Dataset.class);
	}
	
	public static String getString(WebTarget target, String path) {
		return target.path(path).request().get(String.class);
	}
	
	public static int get(WebTarget target, String path) {
		return target.path(path).request().get(Response.class).getStatus();
	}
}
