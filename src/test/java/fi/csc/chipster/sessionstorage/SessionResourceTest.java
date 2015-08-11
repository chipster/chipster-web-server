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
import fi.csc.chipster.sessionstorage.model.Session;

public class SessionResourceTest {

    public static final String path = "sessions";
	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;
    private WebTarget target;
	private TestServer server;

    @Before
    public void setUp() throws Exception {
    	server = new TestServer();
        target = server.getTarget(this);
    }

    @After
    public void tearDown() throws Exception {
    	server.stop(this);
    }

    @Test
    public void post() throws JsonGenerationException, JsonMappingException, IOException {
    	
    	postSession(target);                          
    }
    
    public static String postSession(WebTarget target) {
    	Session session = RestUtils.getRandomSession();
    	Response response = target.path(path).request(JSON).post(Entity.entity(session, JSON),Response.class);
        assertEquals(201, response.getStatus());
        
        return path + "/" + new File(response.getLocation().getPath()).getName();
	}


	@Test
    public void get() throws JsonGenerationException, JsonMappingException, IOException {
        
		String objPath = postSession(target);        
        Session session2 = target.path(objPath).request().get(Session.class);        
        assertEquals(true, session2 != null);
    }
	
	@Test
    public void getAll() {
        
		String id1 = new File(postSession(target)).getName();
		String id2 = new File(postSession(target)).getName();
		
        String json = target.path(path).request().get(String.class);        
        assertEquals(json == null, false);
        
        //TODO parse json
        assertEquals(true, json.contains(id1));
        assertEquals(true, json.contains(id2));
    }
	
	@Test
    public void put() {
        
		String objPath = postSession(target);
		Session newSession = RestUtils.getRandomSession();
		Response response = target.path(objPath).request(JSON).put(Entity.entity(newSession, JSON),Response.class);        
        assertEquals(204, response.getStatus());
    }
	
	@Test
    public void delete() {
        
		String objPath = postSession(target);
		Response response = target.path(objPath).request().delete(Response.class);        
        assertEquals(204, response.getStatus());
    }	
}
