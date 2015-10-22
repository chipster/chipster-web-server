package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.util.HashSet;
import java.util.UUID;

import javax.ws.rs.ForbiddenException;
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
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.sessiondb.resource.Events;

public class AdminResourceTest {

	private static final String EVENTS_PATH = "/jobs/events";
	
	private static TestServerLauncher launcher;
	private static WebTarget user1Target;
	private static WebTarget schedulerTarget;
	private static WebTarget compTarget;
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
    public void auth() throws InterruptedException {
    	
    	try {
    		new AsyncEventInput(user1Target, EVENTS_PATH, Events.EVENT_NAME);
    		assertEquals(true, false);
    	} catch (ForbiddenException e) { }
    	
    	try {    		
    		new AsyncEventInput(unparseableTokenTarget, EVENTS_PATH, Events.EVENT_NAME);
    		assertEquals(true, false);
    	} catch (NotAuthorizedException e) { }
    	
    	try {    		
    		new AsyncEventInput(tokenFailTarget,  EVENTS_PATH, Events.EVENT_NAME);
    		assertEquals(true, false);
    	} catch (NotFoundException e) {
    	}
    	
    	try {
    		new AsyncEventInput(authFailTarget, EVENTS_PATH, Events.EVENT_NAME);
    		assertEquals(true, false);
    	} catch (NotAuthorizedException e) { }

    	try {
    		new AsyncEventInput(noAuthTarget, EVENTS_PATH, Events.EVENT_NAME);        
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
    public void postJobEventToScheduler() throws InterruptedException {
		postJob(schedulerTarget);
	}
	
	@Test
    public void postJobEventToComp() throws InterruptedException {
		postJob(compTarget);
	}
    
    public void postJob(WebTarget eventTarget) throws InterruptedException {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	
    	AsyncEventInput eventInput = new AsyncEventInput(eventTarget, EVENTS_PATH, Events.EVENT_NAME);
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
    public void putJobEventToScheduler() throws InterruptedException {
    	putJob(schedulerTarget);
	}
	
	@Test
    public void putJobEventToComp() throws InterruptedException {
		putJob(compTarget);
	}
    
    public void putJob(WebTarget eventTarget) throws InterruptedException {
    	
    	String sessionPath = SessionResourceTest.postRandomSession(user1Target);
    	String jobPath = JobResourceTest.postRandomJob(user1Target, sessionPath);
    	UUID sessionId = UUID.fromString(RestUtils.basename(sessionPath));
    	UUID jobId = UUID.fromString(RestUtils.basename(jobPath));
    	
    	AsyncEventInput eventInput = new AsyncEventInput(eventTarget, EVENTS_PATH, Events.EVENT_NAME);
    	assertEquals(204, JobResourceTest.put(user1Target, jobPath, RestUtils.getRandomJob()));
        
        InboundEvent event = eventInput.pollAndWait();
        SessionEvent sessionEvent = event.readData(SessionEvent.class);
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.JOB, sessionEvent.getResourceType());
        assertEquals(jobId, sessionEvent.getResourceId());
        assertEquals(EventType.UPDATE, sessionEvent.getType());
        
        close(eventInput);    
    }
	
	private void close(AsyncEventInput eventInput) {
		// why is this so slow?
		//eventInput.close();
	}
}
