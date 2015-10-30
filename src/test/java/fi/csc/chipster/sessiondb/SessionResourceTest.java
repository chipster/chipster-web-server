package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Session;

public class SessionResourceTest {

    public static final String path = "sessions";
	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;
	
	private static TestServerLauncher launcher;
	private static WebTarget user1Target;
	private static WebTarget user2Target;
	private static WebTarget schedulerTarget;
	private static WebTarget compTarget;
	private static WebTarget tokenFailTarget;
	private static WebTarget authFailTarget;
	private static WebTarget noAuthTarget;
	private static WebTarget unparseableTokenTarget;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config, Role.SESSION_DB);
        
        user1Target = launcher.getUser1Target();
        user2Target = launcher.getUser2Target();
        schedulerTarget = launcher.getSchedulerTarget();
        compTarget = launcher.getCompTarget();
        unparseableTokenTarget = launcher.getUnparseableTokenTarget();
        tokenFailTarget = launcher.getTokenFailTarget();
        authFailTarget = launcher.getAuthFailTarget();
        noAuthTarget = launcher.getNoAuthTarget();
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }

    @Test
    public void post() throws IOException {
    	
    	postRandomSession(user1Target);
    	
    	assertEquals(401, post(unparseableTokenTarget, RestUtils.getRandomSession()).getStatus());
    	assertEquals(404, post(tokenFailTarget, RestUtils.getRandomSession()).getStatus());
    	assertEquals(401, post(authFailTarget, RestUtils.getRandomSession()).getStatus());
    	assertEquals(401, post(noAuthTarget, RestUtils.getRandomSession()).getStatus());
    }

	@Test
    public void get() throws IOException {
        
		String obj1Path = postRandomSession(user1Target);
		String obj2Path = postRandomSession(user2Target);
        assertEquals(false, getSession(user1Target, obj1Path) == null);
        // check that user2Target works, other tests rely on it
        assertEquals(false, getSession(user2Target, obj2Path) == null);
        // servers can read any session
        assertEquals(false, getSession(schedulerTarget, obj1Path) == null);
        assertEquals(false, getSession(compTarget, obj1Path) == null);

        // wrong user
        assertEquals(401, get(user1Target, obj2Path));
        assertEquals(401, get(user2Target, obj1Path));
        
        assertEquals(401, get(unparseableTokenTarget, obj1Path));
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
        
        assertEquals(401, get(unparseableTokenTarget, path));
        assertEquals(404, get(tokenFailTarget, path));
        assertEquals(401, get(authFailTarget, path));
        assertEquals(401, get(noAuthTarget, path));
    }
	
	@Test
    public void put() {
        
		String objPath = postRandomSession(user1Target);
		
		Session newSession = RestUtils.getRandomSession();        
        assertEquals(204, put(user1Target, objPath, newSession));
        // servers can modify any session
        assertEquals(204, put(schedulerTarget, objPath, newSession));
        assertEquals(204, put(compTarget, objPath, newSession));
        // wrong user
        assertEquals(401, put(user2Target, objPath, newSession));
        
        assertEquals(401, put(unparseableTokenTarget, objPath, newSession));
        assertEquals(404, put(tokenFailTarget, objPath, newSession));
        assertEquals(401, put(authFailTarget, objPath, newSession));
        assertEquals(401, put(noAuthTarget, objPath, newSession));
    }
	
	@Test
    public void delete() {
        				
		String objPath = postRandomSession(user1Target);

		// wrong user
		assertEquals(401, delete(user2Target, objPath));

		// auth errors
		assertEquals(401, delete(unparseableTokenTarget, objPath));
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
    	session.setSessionId(null);
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
