package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Session;

public class DbPeroformanceTest {

    public static final String path = "sessions";
	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;
    private static WebTarget target;
	private static TestServerLauncher launcher;
	private static int n = 10;
	private static Queue<String> paths;
	
	@BeforeClass
	public static void setUpBeforeClass() throws JsonGenerationException, JsonMappingException, IOException, InterruptedException {
		// once per class
		Config config = new Config();
    	launcher = new TestServerLauncher(config, Role.SESSION_DB);

        target = launcher.getUser1Target();
		paths = postManyParallel();
	}
	
	@AfterClass
	public static void tearDownAfterClass() throws JsonGenerationException, JsonMappingException, IOException, InterruptedException {
		launcher.stop();
	}
    
    @Test
    public void postOne() throws JsonGenerationException, JsonMappingException, IOException {
    	postSession(target);
    }
    
    @Test
    public void postMany() throws JsonGenerationException, JsonMappingException, IOException {
    	for (int i = 0; i < n ; i++) {
    		postSession(target);
    	}
    }
    
    @Test
    public void postManyParallelTest() throws JsonGenerationException, JsonMappingException, IOException, InterruptedException {
    	postManyParallel();
    }
    
    public static Queue<String> postManyParallel() throws JsonGenerationException, JsonMappingException, IOException, InterruptedException {
    	
    	final Queue<String> paths = new ConcurrentLinkedQueue<>();
    	ExecutorService executor = Executors.newFixedThreadPool(10);
    	for (int i = 0; i < n ; i++) {
    		executor.submit(new Runnable() {
				@Override
				public void run() {
					paths.add(postSession(target));		
				}
			});
    	}
    	executor.shutdown();
    	boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
    	assertEquals(false, timeout);
    	return paths;
    }
    
    @Test
    public void getManyParallel() throws JsonGenerationException, JsonMappingException, IOException, InterruptedException {
    	ExecutorService executor = Executors.newFixedThreadPool(10);
    	for (final String path : paths) {
    		executor.submit(new Runnable() {
				@Override
				public void run() {
					getSession(path);
				}
			});
    	}
    	executor.shutdown();
    	boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
    	assertEquals(false, timeout);
    }
    
    @Test
    public void getMany() throws JsonGenerationException, JsonMappingException, IOException {
    	for (String objPath : paths) {
    		getSession(objPath);
    	}
    }
    
    private void getSession(String objPath) {
    	Session sessionObj = target.path(objPath).request().get(Session.class);        
        assertEquals(true, sessionObj != null);
	}

	public static String postSession(WebTarget target) {
    	Session session = RestUtils.getRandomSession();
    	session.setSessionId(null);
    	Response response = target.path(path).request(JSON).post(Entity.entity(session, JSON),Response.class);
        assertEquals(201, response.getStatus());
        
        return path + "/" + new File(response.getLocation().getPath()).getName();
	}


//	@Test
    public void get() throws JsonGenerationException, JsonMappingException, IOException {
        
		String objPath = postSession(target);        
        Session session2 = target.path(objPath).request().get(Session.class);        
        assertEquals(true, session2 != null);
    }
	
//	@Test
    public void getAll() {
        
		String id1 = new File(postSession(target)).getName();
		String id2 = new File(postSession(target)).getName();
		
        String json = target.path(path).request().get(String.class);        
        assertEquals(json == null, false);
        
        //TODO parse json
        assertEquals(true, json.contains(id1));
        assertEquals(true, json.contains(id2));
    }
	
//	@Test
    public void put() {
        
		String objPath = postSession(target);
		Session newSession = RestUtils.getRandomSession();
		Response response = target.path(objPath).request(JSON).put(Entity.entity(newSession, JSON),Response.class);        
        assertEquals(204, response.getStatus());
    }
	
//	@Test
    public void delete() {
        
		String objPath = postSession(target);
		Response response = target.path(objPath).request().delete(Response.class);        
        assertEquals(204, response.getStatus());
    }	
}
