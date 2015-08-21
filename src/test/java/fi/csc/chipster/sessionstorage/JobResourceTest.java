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

import fi.csc.chipster.auth.rest.AuthenticationService;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServer;
import fi.csc.chipster.sessionstorage.model.Job;
import fi.csc.chipster.sessionstorage.rest.SessionStorage;

public class JobResourceTest {

    private static final String JOBS = "/jobs";
    private WebTarget user1Target;
    private WebTarget user2Target;
	private String session1Path;
	private String session2Path;
	private TestServer server;
	private String jobs1Path;
	private String jobs2Path;

    @Before
    public void setUp() throws Exception {
    	server = new TestServer(new SessionStorage(), new AuthenticationService());
        server.startServersIfNecessary();
        user1Target = server.getUser1Target();
        user2Target = server.getUser2Target();
        
        session1Path = SessionResourceTest.postRandomSession(user1Target);
        session2Path = SessionResourceTest.postRandomSession(user2Target);
        jobs1Path = session1Path + JOBS;
        jobs2Path = session2Path + JOBS;
    }

    @After
    public void tearDown() throws Exception {
    	server.stop();
    }           
    
    @Test
    public void post() {
    	postRandomJob(user1Target, session1Path);
    }


	@Test
    public void get() throws JsonGenerationException, JsonMappingException, IOException {
        
		String objPath = postRandomJob(user1Target, session1Path);        
        assertEquals(false, getJob(user1Target, objPath) == null);        
        
        // wrong user
        assertEquals(404, get(user2Target, objPath));
        
        // wrong session
        assertEquals(404, get(user1Target, changeSession(objPath)));
        assertEquals(404, get(user2Target, changeSession(objPath)));
    }
	
	@Test
    public void getAll() {
        
		String id1 = RestUtils.basename(postRandomJob(user1Target, session1Path));
		String id2 = RestUtils.basename(postRandomJob(user1Target, session1Path));
		
		String json = getString(user1Target, jobs1Path);
        assertEquals(false, json == null);
        
        //TODO parse json
        assertEquals(true, json.contains(id1));
        assertEquals(true, json.contains(id2));
        
        // wrong user
        assertEquals(404, get(user2Target, jobs1Path));
        
        // wrong session
        String session2Json = getString(user2Target, jobs2Path);
        //TODO parse json
        assertEquals(false, session2Json.contains(id1));
    }
	
	@Test
    public void put() {
        
		String objPath = postRandomJob(user1Target, session1Path);
		Job newObj = RestUtils.getRandomJob();
        assertEquals(204, put(user1Target, objPath, newObj));
        
        // wrong user
        assertEquals(404, put(user2Target, objPath, newObj));
        
        // wrong session
        assertEquals(404, put(user1Target, changeSession(objPath), newObj));
        assertEquals(404, put(user2Target, changeSession(objPath), newObj));
    }

	@Test
    public void delete() {
        
		String objPath = postRandomJob(user1Target, session1Path);
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
	 
    public static String postRandomJob(WebTarget target, String sessionPath) {
    	Job job = RestUtils.getRandomJob();
    	Response response = post(target, sessionPath + JOBS, job);
        assertEquals(201, response.getStatus());
        
        return sessionPath + JOBS + "/" + RestUtils.basename(response.getLocation().getPath());
	}
	
	public static int delete(WebTarget target, String path) {
		return target.path(path).request().delete(Response.class).getStatus();
	}
	
    public static Response post(WebTarget target, String path, Job job) {
    	return target.path(path).request(MediaType.APPLICATION_JSON_TYPE).post(Entity.entity(job, MediaType.APPLICATION_JSON_TYPE),Response.class);
	}
    
	public static int put(WebTarget target, String path, Job job) {
		return target.path(path).request(MediaType.APPLICATION_JSON_TYPE).put(Entity.entity(job,  MediaType.APPLICATION_JSON_TYPE), Response.class).getStatus();
	}
	
	public static Job getJob(WebTarget target, String path) {
		return target.path(path).request().get(Job.class);
	}
	
	public static String getString(WebTarget target, String path) {
		return target.path(path).request().get(String.class);
	}
	
	public static int get(WebTarget target, String path) {
		return target.path(path).request().get(Response.class).getStatus();
	}
}
