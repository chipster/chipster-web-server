package fi.csc.chipster.sessionstorage;

import static org.junit.Assert.assertEquals;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.media.sse.InboundEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import fi.csc.chipster.auth.rest.AuthenticationService;
import fi.csc.chipster.rest.AsyncEventInput;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServer;
import fi.csc.chipster.sessionstorage.model.SessionEvent;
import fi.csc.chipster.sessionstorage.model.SessionEvent.EventType;
import fi.csc.chipster.sessionstorage.model.SessionEvent.ResourceType;
import fi.csc.chipster.sessionstorage.rest.SessionStorage;

public class EventTest {

    public static final String path = "sessions";
	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;
	
	private TestServer server;
	private WebTarget user1Target;
	private WebTarget user2Target;
	private WebTarget tokenFailTarget;
	private WebTarget authFailTarget;
	private WebTarget noAuthTarget;

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
    public void delete() {
    	
    	String sessionPath = SessionResourceTest.postSession(user1Target);
    	String sessionId = RestUtils.basename(sessionPath);
    	
    	AsyncEventInput eventInput = new AsyncEventInput(user1Target, sessionPath + "/events", "SessionEvent");
    	
    	assertEquals(null, eventInput.poll());
    	
        assertEquals(204, user1Target.path(sessionPath).request().delete(Response.class).getStatus());
        
        InboundEvent event = eventInput.poll();
        SessionEvent sessionEvent = event.readData(SessionEvent.class);
        assertEquals(sessionId, sessionEvent.getSessionId());
        assertEquals(ResourceType.SESSION, sessionEvent.getResourceType());
        assertEquals(EventType.DELETE, sessionEvent.getType());
        
        assertEquals(null, eventInput.poll());
        eventInput.close();    
    }
}
