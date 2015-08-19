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

	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;
    private static final String jobsPath = "jobs";
    private WebTarget user1Target;
    private WebTarget user2Target;
	private String session1Path;
	private String session2Path;
	private TestServer server;

    @Before
    public void setUp() throws Exception {
    	server = new TestServer(new SessionStorage(), new AuthenticationService());
        server.startServersIfNecessary();
        user1Target = server.getUser1Target();
        user2Target = server.getUser2Target();
        
        session1Path = SessionResourceTest.postSession(user1Target) + "/" + jobsPath;
        session2Path = SessionResourceTest.postSession(user2Target) + "/" + jobsPath;
    }

    @After
    public void tearDown() throws Exception {
    	server.stop();
    }           
    
    private String postJob() {
    	Job job = RestUtils.getRandomJob();
    	Response response = user1Target.path(session1Path).request(JSON).post(Entity.entity(job, JSON),Response.class);        
        assertEquals(201, response.getStatus());
        
        return session1Path + "/" + RestUtils.basename(response.getLocation().getPath());
	}
    
    @Test
    public void post() {
    	postJob();
    }


	@Test
    public void get() throws JsonGenerationException, JsonMappingException, IOException {
        
		String objPath = postJob();        
        assertEquals(false, user1Target.path(objPath).request().get(Job.class) == null);        
        
        // wrong user
        assertEquals(404, user2Target.path(objPath).request().get(Response.class).getStatus());
        
        // wrong session
        assertEquals(404, user1Target.path(changeSession(objPath)).request().get(Response.class).getStatus());
        assertEquals(404, user2Target.path(changeSession(objPath)).request().get(Response.class).getStatus());
    }
	
	@Test
    public void getAll() {
        
		String id1 = RestUtils.basename(postJob());
		String id2 = RestUtils.basename(postJob());
		
        String json = user1Target.path(session1Path).request().get(String.class);
        assertEquals(false, json == null);
        
        //TODO parse json
        assertEquals(true, json.contains(id1));
        assertEquals(true, json.contains(id2));
        
        // wrong user
        assertEquals(404, user2Target.path(session1Path).request().get(Response.class).getStatus());
        
        // wrong session
        String session2Json = user2Target.path(session2Path).request().get(String.class);
        //TODO parse json
        assertEquals(false, session2Json.contains(id1));
    }
	
	@Test
    public void put() {
        
		String objPath = postJob();
		Job newObj = RestUtils.getRandomJob();
		assertEquals(204, user1Target.path(objPath).request(JSON).put(Entity.entity(newObj, JSON),Response.class).getStatus());
        
        // wrong user
        assertEquals(404, user2Target.path(objPath).request(JSON).put(Entity.entity(newObj, JSON),Response.class).getStatus());
        
        // wrong session
        assertEquals(404, user1Target.path(changeSession(objPath)).request(JSON).put(Entity.entity(newObj, JSON),Response.class).getStatus());
        assertEquals(404, user2Target.path(changeSession(objPath)).request(JSON).put(Entity.entity(newObj, JSON),Response.class).getStatus());
    }
	
	private String changeSession(String objPath) {
		String session1Id = RestUtils.basename(session1Path.replace("/" + jobsPath, ""));
        String session2Id = RestUtils.basename(session2Path.replace("/" + jobsPath, ""));
        
        return objPath.replace(session1Id, session2Id);
	}

	@Test
    public void delete() {
        
		String objPath = postJob();
		// wrong user
		assertEquals(404, user2Target.path(objPath).request().delete(Response.class).getStatus());
		
		// wrong session
		assertEquals(404, user1Target.path(changeSession(objPath)).request().delete(Response.class).getStatus());
		assertEquals(404, user2Target.path(changeSession(objPath)).request().delete(Response.class).getStatus());
		
		// delete
        assertEquals(204, user1Target.path(objPath).request().delete(Response.class).getStatus());
        
        // doesn't exist anymore
        assertEquals(404, user1Target.path(objPath).request().delete(Response.class).getStatus());
    }
}
