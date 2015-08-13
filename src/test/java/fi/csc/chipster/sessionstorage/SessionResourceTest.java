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
	private WebTarget sessionTarget;
	private WebTarget tokenFailTarget;
	private WebTarget authFailTarget;
	private WebTarget noAuthTarget;

    @Before
    public void setUp() throws Exception {
    	server = new TestServer(new SessionStorage(), new AuthenticationService());
        server.startServersIfNecessary();
        
        sessionTarget = server.getUser1Target();
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
    	
    	postSession(sessionTarget);
    	
    	assertEquals(404, postSessionResponse(tokenFailTarget).getStatus());
    	assertEquals(401, postSessionResponse(authFailTarget).getStatus());
    	assertEquals(401, postSessionResponse(noAuthTarget).getStatus());    	
    }
    
    public static String postSession(WebTarget target) {
    	Response response = postSessionResponse(target);
        assertEquals(201, response.getStatus());
        
        return path + "/" + new File(response.getLocation().getPath()).getName();
	}
    
    public static Response postSessionResponse(WebTarget target) {
    	Session session = RestUtils.getRandomSession();
    	Response response = target.path(path).request(JSON).post(Entity.entity(session, JSON),Response.class);
    	return response;
	}


	@Test
    public void get() throws JsonGenerationException, JsonMappingException, IOException {
        
		String objPath = postSession(sessionTarget);        
        Session session2 = sessionTarget.path(objPath).request().get(Session.class);        
        assertEquals(false, session2 == null);
        
        assertEquals(404, tokenFailTarget.path(objPath).request().get(Response.class).getStatus());
        assertEquals(401, authFailTarget.path(objPath).request().get(Response.class).getStatus());
        assertEquals(401, noAuthTarget.path(objPath).request().get(Response.class).getStatus());
    }
	
	@Test
    public void getAll() {
        
		String id1 = new File(postSession(sessionTarget)).getName();
		String id2 = new File(postSession(sessionTarget)).getName();
		
        String json = sessionTarget.path(path).request().get(String.class);        
        assertEquals(json == null, false);
        
        //TODO parse json
        assertEquals(true, json.contains(id1));
        assertEquals(true, json.contains(id2));
        
        assertEquals(404, tokenFailTarget.path(path).request().get(Response.class).getStatus());
        assertEquals(401, authFailTarget.path(path).request().get(Response.class).getStatus());
        assertEquals(401, noAuthTarget.path(path).request().get(Response.class).getStatus());
    }
	
	@Test
    public void put() {
        
		String objPath = postSession(sessionTarget);
		Session newSession = RestUtils.getRandomSession();
		Response response = sessionTarget.path(objPath).request(JSON).put(Entity.entity(newSession, JSON),Response.class);        
        assertEquals(204, response.getStatus());
        
        assertEquals(404, tokenFailTarget.path(objPath).request(JSON).put(Entity.entity(newSession, JSON),Response.class).getStatus());
        assertEquals(401, authFailTarget.path(objPath).request(JSON).put(Entity.entity(newSession, JSON),Response.class).getStatus());
        assertEquals(401, noAuthTarget.path(objPath).request(JSON).put(Entity.entity(newSession, JSON),Response.class).getStatus());
    }
	
	@Test
    public void delete() {
        				
		String objPath = postSession(sessionTarget);

        assertEquals(204, sessionTarget.path(objPath).request().delete(Response.class).getStatus());
        assertEquals(404, sessionTarget.path(objPath).request().delete(Response.class).getStatus());
        assertEquals(404, tokenFailTarget.path(objPath).request().delete(Response.class).getStatus());
        assertEquals(401, authFailTarget.path(objPath).request().delete(Response.class).getStatus());
        assertEquals(401, noAuthTarget.path(objPath).request().delete(Response.class).getStatus());
    }	
}
