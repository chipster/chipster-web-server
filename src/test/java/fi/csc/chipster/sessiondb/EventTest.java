package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.websocket.MessageHandler;

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
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;

public class EventTest {
	
	private static TestServerLauncher launcher;
	
	private static String uri;
	private static Config config;

	private static String token;
	private static String token2;
	private static String schedulerToken;
	private static SessionDbClient user1Client;	

    @BeforeClass
    public static void setUp() throws Exception {
    	config = new Config();
    	launcher = new TestServerLauncher(config);
        ServiceLocatorClient serviceLocator = new ServiceLocatorClient(config);
        uri = serviceLocator.get(Role.SESSION_DB_EVENTS).get(0) + SessionDb.EVENTS_PATH + "/";
        token = new AuthenticationClient(serviceLocator, "client", "clientPassword").getToken().toString();
        token2 = new AuthenticationClient(serviceLocator, "client2", "client2Password").getToken().toString();
        schedulerToken = new AuthenticationClient(serviceLocator, "scheduler", "schedulerPassword").getToken().toString();
        user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token());
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
    	
    	String sessionId = user1Client.createSession(RestUtils.getRandomSession()).toString();
    	
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
    	
    	String sessionId = user1Client.createSession(RestUtils.getRandomSession()).toString();
    	
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
    	
    	UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);    	
    	
    	user1Client.deleteSession(sessionId);
    	
        // wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));        
        SessionEvent sessionEvent = RestUtils.parseJson(SessionEvent.class, messages.get(0));
        
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.AUTHORIZATION, sessionEvent.getResourceType());
        assertEquals(sessionId, sessionEvent.getResourceId());
        assertEquals(EventType.DELETE, sessionEvent.getType());
        
        client.shutdown();
    }
    
    @Test
    public void reconnect() throws Exception {    	
    	
    	UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
    	
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
    		
    	user1Client.deleteSession(sessionId);
    	
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
    			
		Session session = RestUtils.getRandomSession();
    	UUID sessionId = user1Client.createSession(session);

    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);

    	session.setName("new name");
    	user1Client.updateSession(session);
    	
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
		
    	UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);

    	UUID datasetId = user1Client.createDataset(sessionId, RestUtils.getRandomDataset());
        
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
    	
    	UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
    	Dataset dataset = RestUtils.getRandomDataset();
    	UUID datasetId = user1Client.createDataset(sessionId, dataset);
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);
    	
    	dataset.setName("new name");
    	user1Client.updateDataset(sessionId, dataset);
    	
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
		
    	UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
    	UUID datasetId = user1Client.createDataset(sessionId, RestUtils.getRandomDataset());
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);
    	
    	user1Client.deleteDataset(sessionId, datasetId);
        
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
		
    	UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);

    	UUID jobId = user1Client.createJob(sessionId, RestUtils.getRandomJob());
        
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
    	
    	UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
    	Job job = RestUtils.getRandomJob();
    	UUID jobId = user1Client.createJob(sessionId, job);
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(uri + sessionId.toString(), messages, latch, false, eventToken);
    	
    	job.setToolName("new name");
    	user1Client.updateJob(sessionId, job);
        
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
		
    	UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
    	UUID jobId = user1Client.createJob(sessionId, RestUtils.getRandomJob());
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(sessionId.toString(), messages, latch);
    	
    	user1Client.deleteJob(sessionId, jobId);
    	
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
