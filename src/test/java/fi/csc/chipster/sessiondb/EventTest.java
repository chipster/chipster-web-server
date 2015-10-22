package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.MessageHandler;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.WebTarget;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AsyncEventInput;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.rest.WebsocketClient;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.sessiondb.resource.Events;

public class EventTest {

	private static final String EVENTS_PATH = "/events";
	
	private static TestServerLauncher launcher;
	private static WebTarget user1Target;
	private static WebTarget user2Target;
	private static WebTarget tokenFailTarget;
	private static WebTarget authFailTarget;
	private static WebTarget noAuthTarget;
	private static WebTarget unparseableTokenTarget;

	private static String uri;
	private static Config config;
	
	HashSet<AsyncEventInput> inputsToClose = new HashSet<>();


    @BeforeClass
    public static void setUp() throws Exception {
    	config = new Config();
    	launcher = new TestServerLauncher(config, Role.SESSION_DB);
        ServiceLocatorClient serviceLocator = new ServiceLocatorClient(config);
        uri = serviceLocator.get(Role.SESSION_DB_EVENTS).get(0);
        user1Target = launcher.getUser1Target();
//        user2Target = launcher.getUser2Target();
//        unparseableTokenTarget = launcher.getUnparseableTokenTarget();
//        tokenFailTarget = launcher.getTokenFailTarget();
//        authFailTarget = launcher.getAuthFailTarget();
//        noAuthTarget = launcher.getNoAuthTarget();
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }
    
    //@Test
    public void auth() throws InterruptedException {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	
    	try {
    		new AsyncEventInput(user2Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
    		assertEquals(true, false);
    	} catch (NotFoundException e) { }
    	
    	try {    		
    		new AsyncEventInput(unparseableTokenTarget, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
    		assertEquals(true, false);
    	} catch (NotAuthorizedException e) { }
    	
    	try {    		
    		new AsyncEventInput(tokenFailTarget, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
    		assertEquals(true, false);
    	} catch (NotFoundException e) {
    	}
    	
    	try {
    		new AsyncEventInput(authFailTarget, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
    		assertEquals(true, false);
    	} catch (NotAuthorizedException e) { }

    	try {
    		new AsyncEventInput(noAuthTarget, sessionPath + EVENTS_PATH, Events.EVENT_NAME);        
    		assertEquals(true, false);
    	} catch (NotAuthorizedException e) { }
    }
    
    @Test
    public void connectionClose() throws InterruptedException {
    	
//    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
//    	String sessionId = RestUtils.basename(sessionPath);
//    	
//    	AsyncEventInput eventInput = new AsyncEventInput(user1Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
//    	
//    	assertEquals(null, eventInput.pollAndWait());
//    	
//    	System.out.println(eventInput.getEventSource().isOpen());
//    	// close broadcasters
//    	((SessionStorage)serverLauncher.getServer()).close();
//    	
//    	assertEquals(null, eventInput.pollAndWait());
//    	
//    	System.out.println(eventInput.getEventSource().isOpen());
//    	Thread.sleep(3000);
//    	System.out.println(eventInput.getEventSource().isOpen());
//    	
//        assertEquals(204, SessionResourceTest.delete(user1Target, sessionPath));
//        
//        InboundEvent event = eventInput.pollAndWait();
//        System.out.println(event);
//        SessionEvent sessionEvent = event.readData(SessionEvent.class);
//        assertEquals(sessionId, sessionEvent.getSessionId());
//        
//        close(eventInput);    
    }
    
    @Test
    public void deleteSession() throws Exception {    	
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebsocketClient client = getClient(sessionPath, messages, latch);    	
    	
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
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebsocketClient client = getClient(sessionPath, messages, latch, true);
    	
//    	launcher.getServerLauncher().getSessionDb().close();
//    	launcher.getServerLauncher().getSessionDb().startServer();
    	
    	launcher.getServerLauncher().getSessionDb().getPubSubServer().stop();
    	launcher.getServerLauncher().getSessionDb().getPubSubServer().init();
    	launcher.getServerLauncher().getSessionDb().getPubSubServer().start();
    	
    	// there is a race condition between the connection initialization and the session deletion
    	// give a small head start for the former
    	Thread.sleep(1000);
    	
        assertEquals(204, SessionResourceTest.delete(user1Target, sessionPath));
        
        // wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));        
        assertEquals(1, messages.size());
        
        client.shutdown();
    }
    
    private WebsocketClient getClient(String sessionPath, final ArrayList<String> messages, final CountDownLatch latch) throws Exception {
    	return getClient(sessionPath, messages, latch, false);
    }
    
    private WebsocketClient getClient(String sessionPath, final ArrayList<String> messages, final CountDownLatch latch, boolean retry) throws Exception {
    	WebsocketClient client =  new WebsocketClient(uri + sessionPath + EVENTS_PATH, new MessageHandler.Whole<String>() {
    		@Override
    		public void onMessage(String msg) {
    			messages.add(msg);
    			latch.countDown();
    		}
    	}, retry);
    	
    	// no events yet
    	assertEquals(1, latch.getCount());
    	    	
    	return client;
	}

	@Test
    public void putSession() throws Exception {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	Session newSession = RestUtils.getRandomSession();
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebsocketClient client = getClient(sessionPath, messages, latch);
    	
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
    	WebsocketClient client = getClient(sessionPath, messages, latch);
    	
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
    	WebsocketClient client = getClient(sessionPath, messages, latch);
    	
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
    	WebsocketClient client = getClient(sessionPath, messages, latch);
    	
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
    	WebsocketClient client = getClient(sessionPath, messages, latch);
    	
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
    public void putJob() throws Exception {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	String jobPath = JobResourceTest.postRandomJob(user1Target, sessionPath);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	UUID jobId = UUID.fromString(RestUtils.basename(jobPath));
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebsocketClient client = getClient(sessionPath, messages, latch);
    	
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
    	WebsocketClient client = getClient(sessionPath, messages, latch);
    	
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
