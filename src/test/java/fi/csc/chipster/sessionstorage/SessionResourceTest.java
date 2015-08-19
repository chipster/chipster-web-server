package fi.csc.chipster.sessionstorage;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;

import javax.ws.rs.ForbiddenException;
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
import fi.csc.chipster.rest.AuthenticatedTarget;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServer;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.chipster.sessionstorage.rest.SessionStorage;

public class SessionResourceTest {

    public static final String path = "sessions";
	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;
	
	private TestServer server;
	private WebTarget user1Target;
	private WebTarget user2Target;
	private WebTarget tokenFailTarget;
	private WebTarget authFailTarget;
	private WebTarget noAuthTarget;

    @Before
    public void setUp() throws Exception {
    	server = new TestServer(new SessionStorage(), new AuthenticationService());
        server.startServersIfNecessary();
        
        user1Target = server.getUser1Target();
        user2Target = server.getUser2Target();
        tokenFailTarget = server.getTokenFailTarget();
        authFailTarget = server.getAuthFailTarget();
        noAuthTarget = server.getNoAuthTarget();
    }

    @After
    public void tearDown() throws Exception {
    	server.stop();
    }
    
    @Test
    public void authFail() throws JsonGenerationException, JsonMappingException, IOException {
    	try {
    		new AuthenticatedTarget("wrongUsername", "clientPassword").target(new SessionStorage().getBaseUri());
    		assertEquals(true, false);
    	} catch (ForbiddenException e) { 
    	}
    	
    	try {
    		new AuthenticatedTarget("client", "wrongPassword").target(new SessionStorage().getBaseUri());
    		assertEquals(true, false);
    	} catch (ForbiddenException e) {
    	}
    }

    @Test
    public void post() throws JsonGenerationException, JsonMappingException, IOException {
    	
    	postSession(user1Target);
    	
    	assertEquals(404, postSessionResponse(tokenFailTarget).getStatus());
    	assertEquals(401, postSessionResponse(authFailTarget).getStatus());
    	assertEquals(401, postSessionResponse(noAuthTarget).getStatus());    	
    }
    
    public static String postSession(WebTarget target) {
    	Response response = postSessionResponse(target);
        assertEquals(201, response.getStatus());
        
        return path + "/" + RestUtils.basename(response.getLocation().getPath());
	}
    
    public static Response postSessionResponse(WebTarget target) {
    	Session session = RestUtils.getRandomSession();
    	Response response = target.path(path).request(JSON).post(Entity.entity(session, JSON),Response.class);
    	return response;
	}


	@Test
    public void get() throws JsonGenerationException, JsonMappingException, IOException {
        
		String obj1Path = postSession(user1Target);
		String obj2Path = postSession(user2Target);
        assertEquals(false, user1Target.path(obj1Path).request().get(Session.class) == null);
        // check that user2Target works, other tests rely on it
        assertEquals(false, user2Target.path(obj2Path).request().get(Session.class) == null);

        // wrong user
        assertEquals(404, user1Target.path(obj2Path).request().get(Response.class).getStatus());
        assertEquals(404, user2Target.path(obj1Path).request().get(Response.class).getStatus());
        
        assertEquals(404, tokenFailTarget.path(obj1Path).request().get(Response.class).getStatus());
        assertEquals(401, authFailTarget.path(obj1Path).request().get(Response.class).getStatus());
        assertEquals(401, noAuthTarget.path(obj1Path).request().get(Response.class).getStatus());
    }
	
	@Test
    public void getAll() {
        
		String id1 = new File(postSession(user1Target)).getName();
		String id2 = new File(postSession(user1Target)).getName();
		
        String json = user1Target.path(path).request().get(String.class);        
        assertEquals(json == null, false);
        
        //TODO parse json
        assertEquals(true, json.contains(id1));
        assertEquals(true, json.contains(id2));
        
        // wrong user
        String user2Json = user2Target.path(path).request().get(String.class);        
        //TODO parse json
        assertEquals(false, user2Json.contains(id1));
        
        assertEquals(404, tokenFailTarget.path(path).request().get(Response.class).getStatus());
        assertEquals(401, authFailTarget.path(path).request().get(Response.class).getStatus());
        assertEquals(401, noAuthTarget.path(path).request().get(Response.class).getStatus());
    }
	
	@Test
    public void put() {
        
		String objPath = postSession(user1Target);
		
		Session newSession = RestUtils.getRandomSession();        
        assertEquals(204, user1Target.path(objPath).request(JSON).put(Entity.entity(newSession, JSON),Response.class).getStatus());
        // wrong user
        assertEquals(404, user2Target.path(objPath).request(JSON).put(Entity.entity(newSession, JSON),Response.class).getStatus());
        
        assertEquals(404, tokenFailTarget.path(objPath).request(JSON).put(Entity.entity(newSession, JSON),Response.class).getStatus());
        assertEquals(401, authFailTarget.path(objPath).request(JSON).put(Entity.entity(newSession, JSON),Response.class).getStatus());
        assertEquals(401, noAuthTarget.path(objPath).request(JSON).put(Entity.entity(newSession, JSON),Response.class).getStatus());
    }
	
	@Test
    public void delete() {
        				
		String objPath = postSession(user1Target);

		// wrong user
		assertEquals(404, user2Target.path(objPath).request().delete(Response.class).getStatus());

		// auth errors
		assertEquals(404, tokenFailTarget.path(objPath).request().delete(Response.class).getStatus());
		assertEquals(401, authFailTarget.path(objPath).request().delete(Response.class).getStatus());
		assertEquals(401, noAuthTarget.path(objPath).request().delete(Response.class).getStatus());
		
		// delete
        assertEquals(204, user1Target.path(objPath).request().delete(Response.class).getStatus());
        
        // doesn't exist anymore
        assertEquals(404, user1Target.path(objPath).request().delete(Response.class).getStatus());
    }	
}
