package fi.csc.chipster.sessionstorage;

import static org.junit.Assert.assertEquals;

import java.io.File;
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
import fi.csc.chipster.rest.TestServer;
import fi.csc.chipster.sessionstorage.model.Job;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.chipster.sessionstorage.rest.SessionResource;
import fi.csc.chipster.sessionstorage.rest.SessionStorage;

public class JobResourceTest {

	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;
    private static final String jobsPath = "jobs";
    private WebTarget target;
	private String path;
	private TestServer server;

    @Before
    public void setUp() throws Exception {
    	server = new TestServer(new SessionStorage());
        target = server.getTarget();
        
        path = SessionResourceTest.postSession(target) + "/" + jobsPath;
    }

    @After
    public void tearDown() throws Exception {
    	server.stop(this);
    }           
    
    private String postJob() {
    	Job job = RestUtils.getRandomJob();
    	Response response = target.path(path).request(JSON).post(Entity.entity(job, JSON),Response.class);        
        assertEquals(201, response.getStatus());
        
        return path + "/" + new File(response.getLocation().getPath()).getName();
	}
    
    @Test
    public void post() {
    	postJob();
    }


	@Test
    public void get() throws JsonGenerationException, JsonMappingException, IOException {
        
		String objPath = postJob();        
        Job dataset2 = target.path(objPath).request().get(Job.class);        
        assertEquals(true, dataset2 != null);
    }
	
	@Test
    public void getAll() {
        
		String id1 = new File(postJob()).getName();
		String id2 = new File(postJob()).getName();
		
        String json = target.path(path).request().get(String.class);
        assertEquals(json == null, false);
        
        //TODO parse json
        assertEquals(true, json.contains(id1));
        assertEquals(true, json.contains(id2));
    }
	
	@Test
    public void put() {
        
		String objPath = postJob();
		Job newObj = RestUtils.getRandomJob();
		Response response = target.path(objPath).request(JSON).put(Entity.entity(newObj, JSON),Response.class);
        assertEquals(204, response.getStatus());
    }
	
	@Test
    public void delete() {
        
		String objPath = postJob();
		Response response = target.path(objPath).request().delete(Response.class);        
        assertEquals(204, response.getStatus());
    }
}
