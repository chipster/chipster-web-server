package fi.csc.chipster.sessionstorage;

import static org.junit.Assert.assertEquals;

import java.util.HashSet;

import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.WebTarget;

import org.glassfish.jersey.media.sse.InboundEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import fi.csc.chipster.auth.rest.AuthenticationService;
import fi.csc.chipster.rest.AsyncEventInput;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServer;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.chipster.sessionstorage.model.SessionEvent;
import fi.csc.chipster.sessionstorage.model.SessionEvent.EventType;
import fi.csc.chipster.sessionstorage.model.SessionEvent.ResourceType;
import fi.csc.chipster.sessionstorage.rest.Events;
import fi.csc.chipster.sessionstorage.rest.SessionStorage;

public class EventTest {

	private static final String EVENTS_PATH = "/events";
	
	private TestServer server;
	private WebTarget user1Target;
	private WebTarget user2Target;
	private WebTarget tokenFailTarget;
	private WebTarget authFailTarget;
	private WebTarget noAuthTarget;
	
	HashSet<AsyncEventInput> inputsToClose = new HashSet<>();

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
    public void auth() throws InterruptedException {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	
    	try {
    		new AsyncEventInput(user2Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
    		assertEquals(true, false);
    	} catch (NotFoundException e) { }
    	
    	try {    		
    		new AsyncEventInput(tokenFailTarget, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
    		assertEquals(true, false);
    	} catch (NotFoundException e) { }
    	
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
    public void deleteSession() throws InterruptedException {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	String sessionId = RestUtils.basename(sessionPath);
    	
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
    	String sessionId = RestUtils.basename(sessionPath);
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
    	String sessionId = RestUtils.basename(sessionPath);
    	
    	AsyncEventInput eventInput = new AsyncEventInput(user1Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
        String datasetPath = DatasetResourceTest.postRandomDataset(user1Target, sessionPath);
        String datasetId = RestUtils.basename(datasetPath);
        
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
    	String sessionId = RestUtils.basename(sessionPath);
    	String datasetId = RestUtils.basename(datasetPath);
    	
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
    	String sessionId = RestUtils.basename(sessionPath);
    	String datasetId = RestUtils.basename(datasetPath);
    	
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
    	String sessionId = RestUtils.basename(sessionPath);
    	
    	AsyncEventInput eventInput = new AsyncEventInput(user1Target, sessionPath + EVENTS_PATH, Events.EVENT_NAME);
        String jobPath = JobResourceTest.postRandomJob(user1Target, sessionPath);
        String jobId = RestUtils.basename(jobPath);
        
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
    	String sessionId = RestUtils.basename(sessionPath);
    	String jobId = RestUtils.basename(jobPath);
    	
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
    	String sessionId = RestUtils.basename(sessionPath);
    	String jobId = RestUtils.basename(jobPath);
    	
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
