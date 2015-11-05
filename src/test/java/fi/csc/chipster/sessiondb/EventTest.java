package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.websocket.MessageHandler;
import javax.ws.rs.client.WebTarget;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.rest.websocket.WebSocketClient;
import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketErrorException;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;

public class EventTest {
	
	private static TestServerLauncher launcher;
	private static WebTarget user1Target;	

	private static String uri;
	private static Config config;

	private static String token;
	private static String token2;
	private static String schedulerToken;	

    @BeforeClass
    public static void setUp() throws Exception {
    	config = new Config();
    	launcher = new TestServerLauncher(config);
        ServiceLocatorClient serviceLocator = new ServiceLocatorClient(config);
        uri = serviceLocator.get(Role.SESSION_DB_EVENTS).get(0) + SessionDb.EVENTS_PATH + "/";
        token = new AuthenticationClient(serviceLocator, "client", "clientPassword").getToken().toString();
        token2 = new AuthenticationClient(serviceLocator, "client2", "client2Password").getToken().toString();
        schedulerToken = new AuthenticationClient(serviceLocator, "scheduler", "schedulerPassword").getToken().toString();
        user1Target = launcher.getUser1Target(Role.SESSION_DB);
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }
    
    @Test
    public void authErrors() throws Exception {
    	auth(false);    	
    }
    
    @Test
    public void authErrorsCancelRetry() throws Exception {
    	auth(true);    	
    }
    
    public void auth(boolean retry) throws Exception {
    	
    	String sessionId = RestUtils.basename(SessionResourceTest.postRandomSession(user1Target));
    	
    	ArrayList<String> messages = new ArrayList<>(); 
    	CountDownLatch latch = new CountDownLatch(1);
    	
    	// jobs topic with client credentials
    	try {       
    		getTestClient(uri + SessionDb.JOBS_TOPIC, messages, latch, retry, token);
    		assertEquals(true, false);
    	} catch (WebSocketErrorException e) {
    	}
    	
    	// wrong user
    	try {       
    		getTestClient(uri + sessionId, messages, latch, retry, token2);
    		assertEquals(true, false);
    	} catch (WebSocketErrorException e) {
    	}    	
    	
    	// unparseable token
    	try {       
    		getTestClient(uri + sessionId, messages, latch, retry, "unparseableToken");
    		assertEquals(true, false);
    	} catch (WebSocketErrorException e) {
    	}
    	
    	// wrong token
    	try {       
    		getTestClient(uri + sessionId, messages, latch, retry, RestUtils.createId());
    		assertEquals(true, false);
    	} catch (WebSocketErrorException e) {
    	}    

    	// no token
    	try {       
    		getTestClient(uri + sessionId, messages, latch, retry, null);
    		assertEquals(true, false);
    	} catch (WebSocketErrorException e) {
    	}
    }
    
    @Test
    public void connectionClose() throws Exception {
    	
    	String sessionId = RestUtils.basename(SessionResourceTest.postRandomSession(user1Target));
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(uri + sessionId, messages, latch, false, token);
    	
    	launcher.getServerLauncher().getSessionDb().getPubSubServer().stop();
    	
    	// should we have a hook for connection close? now we can only check
    	// that attempt to use the connection throws an appropriate exception 
    	try {
    		client.ping();
    		assertEquals(true, false);
    	} catch (IllegalStateException e) { }
    	
        // start server again
        launcher.getServerLauncher().getSessionDb().getPubSubServer().init();
    	launcher.getServerLauncher().getSessionDb().getPubSubServer().start();
    	
    }
    
    @Test
    public void deleteSession() throws Exception {    	
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);    	
    	
        assertEquals(204, SessionResourceTest.delete(user1Target, sessionPath));
        
        // wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));        
        SessionEvent sessionEvent = RestUtils.parseJson(SessionEvent.class, messages.get(0));
        
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.SESSION, sessionEvent.getResourceType());
        assertEquals(sessionId, sessionEvent.getResourceId());
        assertEquals(EventType.DELETE, sessionEvent.getType());
        
        client.shutdown();
    }
    
    @Test
    public void reconnect() throws Exception {    	
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(uri + sessionId, messages, latch, true, token);
    	
    	launcher.getServerLauncher().getSessionDb().getPubSubServer().stop();
    	launcher.getServerLauncher().getSessionDb().getPubSubServer().init();
    	launcher.getServerLauncher().getSessionDb().getPubSubServer().start();
    	
    	// wait for reconnection
    	boolean pong = false;
    	for (int i = 0; i < 100; i++) {
	    	try {
	    		client.ping();
	    		pong = true;
	    		break;
	    	} catch (IllegalStateException e) {
	    		Thread.sleep(100);
	    	}
    	}
    	if (!pong) {
    		throw new TimeoutException("timeout while waiting for reconnect");
    	}
    		
        assertEquals(204, SessionResourceTest.delete(user1Target, sessionPath));
        
        // wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));        
        assertEquals(1, messages.size());
        
        client.shutdown();
    }
    
    private WebSocketClient getTestClient(String topic, final ArrayList<String> messages, final CountDownLatch latch) throws Exception {
    	return getTestClient(uri + topic, messages, latch, false, token);
    }
    
    public static WebSocketClient getTestClient(String requestUri, final ArrayList<String> messages, final CountDownLatch latch, boolean retry, String token) throws Exception {

    	if (token != null) {
    		requestUri += "?token=" + token;
    	}
    	WebSocketClient client =  new WebSocketClient(requestUri, new MessageHandler.Whole<String>() {
    		@Override
    		public void onMessage(String msg) {
    			messages.add(msg);
    			latch.countDown();
    		}
    	}, retry, "websocket-test-client");
    	
    	// no events yet
    	assertEquals(1, latch.getCount());
    	
    	// wait until the connection is really working
    	client.ping();
    	    	
    	return client;
	}

	@Test
    public void putSession() throws Exception {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	Session newSession = RestUtils.getRandomSession();
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);
    	
        assertEquals(204, SessionResourceTest.put(user1Target, sessionPath, newSession));
        
        // wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));   
        SessionEvent sessionEvent = RestUtils.parseJson(SessionEvent.class, messages.get(0));
        
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.SESSION, sessionEvent.getResourceType());
        assertEquals(sessionId, sessionEvent.getResourceId());
        assertEquals(EventType.UPDATE, sessionEvent.getType());
        
        client.shutdown();
    }

	@Test
    public void postDataset() throws Exception {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);
    	
        String datasetPath = DatasetResourceTest.postRandomDataset(user1Target, sessionPath);
        UUID datasetId = UUID.fromString(RestUtils.basename(datasetPath));
        
        // wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));   
        SessionEvent sessionEvent = RestUtils.parseJson(SessionEvent.class, messages.get(0));
        
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.DATASET, sessionEvent.getResourceType());
        assertEquals(datasetId, sessionEvent.getResourceId());
        assertEquals(EventType.CREATE, sessionEvent.getType());
        
        client.shutdown();
    }
	
	@Test
    public void putDataset() throws Exception {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	String datasetPath = DatasetResourceTest.postRandomDataset(user1Target, sessionPath);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	UUID datasetId = UUID.fromString(RestUtils.basename(datasetPath));
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);
    	
    	assertEquals(204, DatasetResourceTest.put(user1Target, datasetPath, RestUtils.getRandomDataset()));
        
    	// wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));   
        SessionEvent sessionEvent = RestUtils.parseJson(SessionEvent.class, messages.get(0));
        
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.DATASET, sessionEvent.getResourceType());
        assertEquals(datasetId, sessionEvent.getResourceId());
        assertEquals(EventType.UPDATE, sessionEvent.getType());
        
        client.shutdown();    
    }
	
	@Test
    public void deleteDataset() throws Exception {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	String datasetPath = DatasetResourceTest.postRandomDataset(user1Target, sessionPath);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	UUID datasetId = UUID.fromString(RestUtils.basename(datasetPath));
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);
    	
    	assertEquals(204, DatasetResourceTest.delete(user1Target, datasetPath));
        
    	// wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));   
        SessionEvent sessionEvent = RestUtils.parseJson(SessionEvent.class, messages.get(0));
        
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.DATASET, sessionEvent.getResourceType());
        assertEquals(datasetId, sessionEvent.getResourceId());
        assertEquals(EventType.DELETE, sessionEvent.getType());
        
        client.shutdown();
    }
	
	@Test
    public void postJob() throws Exception {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);
    	
        String jobPath = JobResourceTest.postRandomJob(user1Target, sessionPath);
        UUID jobId = UUID.fromString(RestUtils.basename(jobPath));
        
        // wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));   
        SessionEvent sessionEvent = RestUtils.parseJson(SessionEvent.class, messages.get(0));
        
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.JOB, sessionEvent.getResourceType());
        assertEquals(jobId, sessionEvent.getResourceId());
        assertEquals(EventType.CREATE, sessionEvent.getType());
        
        client.shutdown();
    }
	
	@Test
	public void putJobAsClientSubscriber() throws Exception {
		putJob(token);
	}
	
	@Test
	public void putJobAsSchedulerSubscriber() throws Exception {
		putJob(schedulerToken);
	}
	
    public void putJob(String eventToken) throws Exception {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	String jobPath = JobResourceTest.postRandomJob(user1Target, sessionPath);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	UUID jobId = UUID.fromString(RestUtils.basename(jobPath));
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(uri + sessionId.toString(), messages, latch, false, eventToken);
    	
    	assertEquals(204, JobResourceTest.put(user1Target, jobPath, RestUtils.getRandomJob()));
        
    	// wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));   
        SessionEvent sessionEvent = RestUtils.parseJson(SessionEvent.class, messages.get(0));
        
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.JOB, sessionEvent.getResourceType());
        assertEquals(jobId, sessionEvent.getResourceId());
        assertEquals(EventType.UPDATE, sessionEvent.getType());
        
        client.shutdown();    
    }
	
	@Test
    public void deleteJob() throws Exception {
    	
		String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	String jobPath = JobResourceTest.postRandomJob(user1Target, sessionPath);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	UUID jobId = UUID.fromString(RestUtils.basename(jobPath));
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);
    	
    	assertEquals(204, JobResourceTest.delete(user1Target, jobPath));
        
    	// wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));   
        SessionEvent sessionEvent = RestUtils.parseJson(SessionEvent.class, messages.get(0));
        
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.JOB, sessionEvent.getResourceType());
        assertEquals(jobId, sessionEvent.getResourceId());
        assertEquals(EventType.DELETE, sessionEvent.getType());
        
        client.shutdown();   
    }
}
