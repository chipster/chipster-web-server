package fi.csc.chipster.servicelocator;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServerLauncher;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.servicelocator.resource.ServiceResource;
import fi.csc.chipster.sessionstorage.model.Session;

public class ServiceResourceTest {

    public static final String path = ServiceResource.SERVICES;
	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;
	
	private ServerLauncher launcher;
	private WebTarget user1Target;
	private WebTarget serverTarget;
	private WebTarget tokenFailTarget;
	private WebTarget authFailTarget;
	private WebTarget noAuthTarget;

    @Before
    public void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new ServerLauncher(config, null, Role.SERVICE_LOCATOR);
        launcher.startServersIfNecessary();
        
        noAuthTarget = launcher.getNoAuthTarget();
        user1Target = launcher.getUser1Target();
        serverTarget = launcher.getSessionStorageUserTarget();
        
        tokenFailTarget = launcher.getTokenFailTarget();
        authFailTarget = launcher.getAuthFailTarget();
    }

    @After
    public void tearDown() throws Exception {
    	launcher.stop();
    }

    @Test
    public void post() throws JsonGenerationException, JsonMappingException, IOException {
    	
    	postRandomService(serverTarget);
    	
    	// only servers are allowed to register services
    	assertEquals(403, post(noAuthTarget, RestUtils.getRandomSession()).getStatus());
    	assertEquals(403, post(user1Target, RestUtils.getRandomSession()).getStatus());
    	
    	assertEquals(404, post(tokenFailTarget, RestUtils.getRandomSession()).getStatus());
    	assertEquals(401, post(authFailTarget, RestUtils.getRandomSession()).getStatus());
    }
	
	@Test
    public void getAll() {
		
		String id1 = RestUtils.basename(postRandomService(serverTarget));
		String id2 = RestUtils.basename(postRandomService(serverTarget));
		
		String json = getString(noAuthTarget, path);
		
		assertEquals(false, json == null);
		
		assertEquals(true, json.contains(id1));
		assertEquals(true, json.contains(id2));
		
		// test client library
		List<Service> services = new ServiceLocatorClient(new Config()).getServices(Role.SESSION_STORAGE);
		
		HashSet<String> ids = new HashSet<>();
		for (Service service : services) {
			ids.add(service.getServiceId());
		}
		
		assertEquals(true, ids.contains(id1));
        assertEquals(true, ids.contains(id2));
                
        assertEquals(404, get(tokenFailTarget, path));
        assertEquals(401, get(authFailTarget, path));
    }
//	
//	@Test
//    public void delete() {
//        				
//		String objPath = postRandomService(user1Target);
//
//		// wrong user
//		assertEquals(404, delete(user2Target, objPath));
//
//		// auth errors
//		assertEquals(404, delete(tokenFailTarget, objPath));
//		assertEquals(401, delete(authFailTarget, objPath));
//		assertEquals(401, delete(noAuthTarget, objPath));
//		
//		// delete
//        assertEquals(204, delete(user1Target, objPath));
//        
//        // doesn't exist anymore
//        assertEquals(404, delete(user1Target, objPath));
//    }
//	
	public static String postRandomService(WebTarget target) {
    	Service service = RestUtils.getRandomService();
    	Response response = target.path(path).request(JSON).post(Entity.entity(service, JSON),Response.class);
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
