package fi.csc.chipster.sessiondb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.rest.websocket.WebSocketClient;
import fi.csc.chipster.rest.websocket.WebSocketClosedException;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.sessiondb.model.SessionState;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.MessageHandler;

public class EventTest {
	
	private static TestServerLauncher launcher;
	
	private static String uri;
	private static Config config;

	private static String token;
	private static String token2;
	private static String schedulerToken;
	private static SessionDbClient user1Client;	

    @BeforeAll
    public static void setUp() throws Exception {
    	config = new Config();
    	launcher = new TestServerLauncher(config);
        ServiceLocatorClient serviceLocator = new ServiceLocatorClient(config);
        uri = serviceLocator.getPublicUri(Role.SESSION_DB_EVENTS) + "/" + SessionDb.EVENTS_PATH + "/";
        token = new AuthenticationClient(serviceLocator, TestServerLauncher.UNIT_TEST_USER1, "clientPassword", Role.CLIENT).getToken().toString();
        token2 = new AuthenticationClient(serviceLocator, TestServerLauncher.UNIT_TEST_USER2, "client2Password", Role.CLIENT).getToken().toString();
        schedulerToken = new AuthenticationClient(serviceLocator, Role.SCHEDULER, Role.SCHEDULER, Role.CLIENT).getToken().toString();
        user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
    }

    @AfterAll
    public static void tearDown() throws Exception {
    	launcher.stop();
    }
    
    @Test
    public void authErrors() throws Exception {
    	authSessionId(false);    	
    }
    
    @Test
    public void authErrorsCancelRetry() throws Exception {
    	authSessionId(true);    	
    }
    
    @Test
    public void authErrorsUserId() throws Exception {
    	authUserEvents(false);    	
    }
    
    @Test
    public void authErrorsUserIdCancelRetry() throws Exception {
    	authUserEvents(true);    	
    }
    
    public void authSessionId(boolean retry) throws Exception {
    	
    	String sessionId = user1Client.createSession(RestUtils.getRandomSession()).toString();
    	
    	ArrayList<String> messages = new ArrayList<>(); 
    	CountDownLatch latch = new CountDownLatch(1);
    	
    	// jobs topic with client credentials
    	try {       
    		getTestClient(uri, SessionDbTopicConfig.JOBS_TOPIC, messages, latch, retry, token);
    		assertEquals(true, false);
    	} catch (WebSocketClosedException e) {
    		assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
    	}
    	
    	// wrong user
    	try {       
    		getTestClient(uri, sessionId, messages, latch, retry, token2);
    		assertEquals(true, false);
    	} catch (WebSocketClosedException e) {
    		assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
    	}    	
    	
    	// unparseable token
    	try {       
    		getTestClient(uri, sessionId, messages, latch, retry, "unparseableToken");
    		assertEquals(true, false);
    	} catch (WebSocketClosedException e) {
    		assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
    	}
    	
    	// wrong token
    	try {       
    		getTestClient(uri, sessionId, messages, latch, retry, RestUtils.createId());
    		assertEquals(true, false);
    	} catch (WebSocketClosedException e) {
    		assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
    	}    

    	// no token
    	try {       
    		getTestClient(uri, sessionId, messages, latch, retry, null);
    		assertEquals(true, false);
    	} catch (WebSocketClosedException e) {
    		assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
    	}
    }
    
    public void authUserEvents(boolean retry) throws Exception {
    	
    	String userId = launcher.getUser1Credentials().getUsername();
    	
    	ArrayList<String> messages = new ArrayList<>(); 
    	CountDownLatch latch = new CountDownLatch(1);    
    	
    	// wrong user
    	try {       
    		getTestClient(uri, userId, messages, latch, retry, token2);
    		assertEquals(true, false);
    		
    	} catch (WebSocketClosedException e) {
    		assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
    	}
    	
    	// unparseable token
    	try {       
    		getTestClient(uri, userId, messages, latch, retry, "unparseableToken");
    		assertEquals(true, false);
    		
    	} catch (WebSocketClosedException e) {
    		assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
    	}
    	
    	// wrong token
    	try {       
    		getTestClient(uri, userId, messages, latch, retry, RestUtils.createId());
    		assertEquals(true, false);
    		
    	} catch (WebSocketClosedException e) {
    		assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
    	}    

    	// no token
    	try {       
    		getTestClient(uri, userId, messages, latch, retry, null);
    		assertEquals(true, false);
    		
    	} catch (WebSocketClosedException e) {
    		assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
    	}
    }
    
    // the server can be stopped only if this test suite started it
    //@Test
    public void connectionClose() throws Exception {
    	
    	String sessionId = user1Client.createSession(RestUtils.getRandomSession()).toString();
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(uri, sessionId, messages, latch, false, token);
    	    	
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
        assertEquals(ResourceType.SESSION, sessionEvent.getResourceType());
        assertEquals(EventType.UPDATE, sessionEvent.getType());
        assertEquals(SessionState.DELETE, SessionState.valueOf(sessionEvent.getState()));
        
        client.shutdown();
    }

    @Test
    public void createSessionUserEvents() throws Exception {    	
    	
    	String userId = launcher.getUser1Credentials().getUsername();
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(userId, messages, latch);    	
    	
    	UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
    	
        // wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));        
        SessionEvent sessionEvent = RestUtils.parseJson(SessionEvent.class, messages.get(0));
        
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.RULE, sessionEvent.getResourceType());
        assertEquals(EventType.CREATE, sessionEvent.getType());
        
        client.shutdown();
        user1Client.deleteSession(sessionId);
    }
    
    @Test
    public void deleteSessionUserEvents() throws Exception {    	
    	
    	UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
    	String userId = launcher.getUser1Credentials().getUsername();
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(userId, messages, latch);    	
    	
    	user1Client.deleteSession(sessionId);
    	
        // wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));        
        SessionEvent sessionEvent = RestUtils.parseJson(SessionEvent.class, messages.get(0));
        
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.RULE, sessionEvent.getResourceType());
        assertEquals(EventType.DELETE, sessionEvent.getType());
        
        client.shutdown();
    }
    
    // the server can be stopped only if this test suite started it
    //@Test
    public void reconnect() throws Exception {    	
    	
    	UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = getTestClient(uri, sessionId.toString(), messages, latch, true, token);
    	
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
    	return getTestClient(uri, topic, messages, latch, false, token);
    }
    
    public static WebSocketClient getTestClient(String requestUri, String topic, final ArrayList<String> messages, final CountDownLatch latch, boolean retry, String token) throws Exception {
    
    	String encodedTopic = "";
    	if (topic != null) {
    		// encode the topic twice to pass Jetty WebSocketUpgradeFilter when there is a slash in the topic name 
    		encodedTopic = URLEncoder.encode(topic, StandardCharsets.UTF_8.toString());
    		encodedTopic = URLEncoder.encode(encodedTopic, StandardCharsets.UTF_8.toString());
    	}
    	
    	CredentialsProvider credentials = null;
    	if (token != null) {
    		credentials = new StaticCredentials(TokenRequestFilter.TOKEN_USER, token);
    	}
    	
    	WebSocketClient client =  new WebSocketClient(requestUri + encodedTopic, new MessageHandler.Whole<String>() {
    		@Override
    		public void onMessage(String msg) {
    			messages.add(msg);
    			latch.countDown();
    		}
    	}, retry, "websocket-test-client", credentials);
    	
    	// no events yet
    	assertEquals(1, latch.getCount());
    	    	
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
    	WebSocketClient client = getTestClient(uri, sessionId.toString(), messages, latch, false, eventToken);
    	
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
