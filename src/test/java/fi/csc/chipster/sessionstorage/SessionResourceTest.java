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
    	
    	postRandomSession(user1Target);
    	
    	assertEquals(404, post(tokenFailTarget, RestUtils.getRandomSession()).getStatus());
    	assertEquals(401, post(authFailTarget, RestUtils.getRandomSession()).getStatus());
    	assertEquals(401, post(noAuthTarget, RestUtils.getRandomSession()).getStatus());    	
    }

	@Test
    public void get() throws JsonGenerationException, JsonMappingException, IOException {
        
		String obj1Path = postRandomSession(user1Target);
		String obj2Path = postRandomSession(user2Target);
        assertEquals(false, getSession(user1Target, obj1Path) == null);
        // check that user2Target works, other tests rely on it
        assertEquals(false, getSession(user2Target, obj2Path) == null);

        // wrong user
        assertEquals(404, get(user1Target, obj2Path));
        assertEquals(404, get(user2Target, obj1Path));
        
        assertEquals(404, get(tokenFailTarget, obj1Path));
        assertEquals(401, get(authFailTarget, obj1Path));
        assertEquals(401, get(noAuthTarget, obj1Path));
    }
	
	@Test
    public void getAll() {
        
		String id1 = new File(postRandomSession(user1Target)).getName();
		String id2 = new File(postRandomSession(user1Target)).getName();
		
        String json = getString(user1Target, path);        
        assertEquals(json == null, false);
        
        //TODO parse json
        assertEquals(true, json.contains(id1));
        assertEquals(true, json.contains(id2));
        
        // wrong user
        String user2Json = getString(user2Target, path);        
        //TODO parse json
        assertEquals(false, user2Json.contains(id1));
        
        assertEquals(404, get(tokenFailTarget, path));
        assertEquals(401, get(authFailTarget, path));
        assertEquals(401, get(noAuthTarget, path));
    }
	
	@Test
    public void put() {
        
		String objPath = postRandomSession(user1Target);
		
		Session newSession = RestUtils.getRandomSession();        
        assertEquals(204, put(user1Target, objPath, newSession));
        // wrong user
        assertEquals(404, put(user2Target, objPath, newSession));
        
        assertEquals(404, put(tokenFailTarget, objPath, newSession));
        assertEquals(401, put(authFailTarget, objPath, newSession));
        assertEquals(401, put(noAuthTarget, objPath, newSession));
    }
	
	@Test
    public void delete() {
        				
		String objPath = postRandomSession(user1Target);

		// wrong user
		assertEquals(404, delete(user2Target, objPath));

		// auth errors
		assertEquals(404, delete(tokenFailTarget, objPath));
		assertEquals(401, delete(authFailTarget, objPath));
		assertEquals(401, delete(noAuthTarget, objPath));
		
		// delete
        assertEquals(204, delete(user1Target, objPath));
        
        // doesn't exist anymore
        assertEquals(404, delete(user1Target, objPath));
    }
	
	public static String postRandomSession(WebTarget target) {
    	Session session = RestUtils.getRandomSession();
    	Response response = target.path(path).request(JSON).post(Entity.entity(session, JSON),Response.class);
        assertEquals(201, response.getStatus());
        
        return path + "/" + RestUtils.basename(response.getLocation().getPath());
	}

	public static int delete(WebTarget target, String path) {
		return target.path(path).request().delete(Response.class).getStatus();
	}
	
	public static int put(WebTarget target, String path, Session newSession) {
		return target.path(path).request(JSON).put(Entity.entity(newSession, JSON),Response.class).getStatus();
	}
	
    public static Response post(WebTarget target, Session session) {
    	return target.path(path).request(MediaType.APPLICATION_JSON_TYPE).post(Entity.entity(session, MediaType.APPLICATION_JSON_TYPE),Response.class);
	}
    
	public static Session getSession(WebTarget target, String path) {
		return target.path(path).request().get(Session.class);
	}
	
	public static String getString(WebTarget target, String path) {
		return target.path(path).request().get(String.class);
	}
	
	public static int get(WebTarget target, String path) {
		return target.path(path).request().get(Response.class).getStatus();
	}
}
