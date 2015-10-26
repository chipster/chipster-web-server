package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.util.HashSet;
import java.util.UUID;

import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.WebTarget;

import org.glassfish.jersey.media.sse.InboundEvent;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AsyncEventInput;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
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
	
	HashSet<AsyncEventInput> inputsToClose = new HashSet<>();


    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config, Role.SESSION_DB);
        
        user1Target = launcher.getUser1Target();
        user2Target = launcher.getUser2Target();
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
    		e.printStackTrace();
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
    public void deleteSession() throws InterruptedException {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	
    	AsyncEventInput eventInput = new AsyncEventInput(user1Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
    	// no events yet
    	assertEquals(null, eventInput.poll());
    	
        assertEquals(204, SessionResourceTest.delete(user1Target, sessionPath));
        
        InboundEvent event = eventInput.pollAndWait();
        SessionEvent sessionEvent = event.readData(SessionEvent.class);
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.SESSION, sessionEvent.getResourceType());
        assertEquals(sessionId, sessionEvent.getResourceId());
        assertEquals(EventType.DELETE, sessionEvent.getType());
        // no more events
        assertEquals(null, eventInput.poll());
        
        close(eventInput);    
    }
    
    @Test
    public void putSession() throws InterruptedException {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	Session newSession = RestUtils.getRandomSession();
    	
    	AsyncEventInput eventInput = new AsyncEventInput(user1Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
    	
        assertEquals(204, SessionResourceTest.put(user1Target, sessionPath, newSession));
        
        InboundEvent event = eventInput.pollAndWait();
        SessionEvent sessionEvent = event.readData(SessionEvent.class);
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.SESSION, sessionEvent.getResourceType());
        assertEquals(sessionId, sessionEvent.getResourceId());
        assertEquals(EventType.UPDATE, sessionEvent.getType());
        
        close(eventInput);    
    }

	@Test
    public void postDataset() throws InterruptedException {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	
    	AsyncEventInput eventInput = new AsyncEventInput(user1Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
        String datasetPath = DatasetResourceTest.postRandomDataset(user1Target, sessionPath);
        UUID datasetId = UUID.fromString(RestUtils.basename(datasetPath));
        
        InboundEvent event = eventInput.pollAndWait();
        SessionEvent sessionEvent = event.readData(SessionEvent.class);
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.DATASET, sessionEvent.getResourceType());
        assertEquals(datasetId, sessionEvent.getResourceId());
        assertEquals(EventType.CREATE, sessionEvent.getType());
        
        close(eventInput);    
    }
	
	@Test
    public void putDataset() throws InterruptedException {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	String datasetPath = DatasetResourceTest.postRandomDataset(user1Target, sessionPath);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	UUID datasetId = UUID.fromString(RestUtils.basename(datasetPath));
    	
    	AsyncEventInput eventInput = new AsyncEventInput(user1Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
    	assertEquals(204, DatasetResourceTest.put(user1Target, datasetPath, RestUtils.getRandomDataset()));
        
        InboundEvent event = eventInput.pollAndWait();
        SessionEvent sessionEvent = event.readData(SessionEvent.class);
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.DATASET, sessionEvent.getResourceType());
        assertEquals(datasetId, sessionEvent.getResourceId());
        assertEquals(EventType.UPDATE, sessionEvent.getType());
        
        close(eventInput);    
    }
	
	@Test
    public void deleteDataset() throws InterruptedException {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	String datasetPath = DatasetResourceTest.postRandomDataset(user1Target, sessionPath);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	UUID datasetId = UUID.fromString(RestUtils.basename(datasetPath));
    	
    	AsyncEventInput eventInput = new AsyncEventInput(user1Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
    	assertEquals(204, DatasetResourceTest.delete(user1Target, datasetPath));
        
        InboundEvent event = eventInput.pollAndWait();
        SessionEvent sessionEvent = event.readData(SessionEvent.class);
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.DATASET, sessionEvent.getResourceType());
        assertEquals(datasetId, sessionEvent.getResourceId());
        assertEquals(EventType.DELETE, sessionEvent.getType());
        
        close(eventInput);    
    }
	
	@Test
    public void postJob() throws InterruptedException {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	
    	AsyncEventInput eventInput = new AsyncEventInput(user1Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
        String jobPath = JobResourceTest.postRandomJob(user1Target, sessionPath);
        UUID jobId = UUID.fromString(RestUtils.basename(jobPath));
        
        InboundEvent event = eventInput.pollAndWait();
        SessionEvent sessionEvent = event.readData(SessionEvent.class);
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.JOB, sessionEvent.getResourceType());
        assertEquals(jobId, sessionEvent.getResourceId());
        assertEquals(EventType.CREATE, sessionEvent.getType());
        
        close(eventInput);    
    }
	
	@Test
    public void putJob() throws InterruptedException {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	String jobPath = JobResourceTest.postRandomJob(user1Target, sessionPath);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	UUID jobId = UUID.fromString(RestUtils.basename(jobPath));
    	
    	AsyncEventInput eventInput = new AsyncEventInput(user1Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
    	assertEquals(204, JobResourceTest.put(user1Target, jobPath, RestUtils.getRandomJob()));
        
        InboundEvent event = eventInput.pollAndWait();
        SessionEvent sessionEvent = event.readData(SessionEvent.class);
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.JOB, sessionEvent.getResourceType());
        assertEquals(jobId, sessionEvent.getResourceId());
        assertEquals(EventType.UPDATE, sessionEvent.getType());
        
        close(eventInput);    
    }
	
	@Test
    public void deleteJob() throws InterruptedException {
    	
		String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	String jobPath = JobResourceTest.postRandomJob(user1Target, sessionPath);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	UUID jobId = UUID.fromString(RestUtils.basename(jobPath));
    	
    	AsyncEventInput eventInput = new AsyncEventInput(user1Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
    	assertEquals(204, JobResourceTest.delete(user1Target, jobPath));
        
        InboundEvent event = eventInput.pollAndWait();
        SessionEvent sessionEvent = event.readData(SessionEvent.class);
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.JOB, sessionEvent.getResourceType());
        assertEquals(jobId, sessionEvent.getResourceId());
        assertEquals(EventType.DELETE, sessionEvent.getType());
        
        close(eventInput);    
    }
	
	private void close(AsyncEventInput eventInput) {
		// why is this so slow?
		//eventInput.close();
	}
}