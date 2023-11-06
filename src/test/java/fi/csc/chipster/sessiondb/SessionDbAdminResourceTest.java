package fi.csc.chipster.sessiondb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Session;

public class SessionDbAdminResourceTest {

	private static TestServerLauncher launcher;
	private static SessionDbAdminClient adminClient;
	private static SessionDbClient user1Client;
	private static SessionDbClient user2Client;

    @BeforeAll
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
        
    	adminClient 		= new SessionDbAdminClient(launcher.getServiceLocatorForAdmin(), launcher.getAdminToken());

    	user1Client 			= new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		user2Client 			= new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);
    }

    @AfterAll
    public static void tearDown() throws Exception {
    	launcher.stop();
    }
    
	@Test
    public void getSessionsForUser() throws IOException, RestException {
		
		Session session1 = RestUtils.getRandomSession();		
		UUID sessionId1 = user1Client.createSession(session1);

		// user1 can access own sessions
		user1Client.getSession(sessionId1);
		
		// user2 can't access user1 sessions
		SessionResourceTest.testGetSession(403, sessionId1, user2Client);
		
		// admin can get all sessions for user1
		String user1IdString = launcher.getUser1Credentials().getUsername();
		
		String json = adminClient.getSessionsForUser(user1IdString);
		
		// getSessions result json should contain at least 1 session
		ObjectMapper mapper = new ObjectMapper();
		List<HashMap<String, Object>> resultMaps = mapper.readValue(json,
                new TypeReference<List<HashMap<String, Object>>>(){});
//		for (Map<String, Object> m: resultMaps) {
//			System.out.println(m.get("name"));
//		}
		assertTrue(resultMaps.size() > 0);
		
	}

	
	@Test
    public void deleteSessionsForUser() throws IOException, RestException {
		
		// create session for user 1
		Session session1 = RestUtils.getRandomSession();		
		UUID sessionId1 = user1Client.createSession(session1);

		// user1 can access own sessions
		user1Client.getSession(sessionId1);
		
		// admin can delete all sessions for user
		String user1IdString = launcher.getUser1Credentials().getUsername();
		adminClient.deleteSessionsForUser(user1IdString);
		
		// no more session1 for user1
		SessionResourceTest.testGetSession(404, sessionId1, user1Client);
		
		// no more sessions at all for user1
		String result = adminClient.getSessionsForUser(user1IdString);
		assertEquals(result, "[]");
	}

	@Test
    public void deleteSessionsForMultipleUsers() throws IOException, RestException {
		
		// create session for user 1
		Session session1 = RestUtils.getRandomSession();
		Session session2 = RestUtils.getRandomSession();
		Session session3 = RestUtils.getRandomSession();
		Session session4 = RestUtils.getRandomSession();
		UUID sessionId1 = user1Client.createSession(session1);
		UUID sessionId2 = user1Client.createSession(session2);
		UUID sessionId3 = user2Client.createSession(session3);
		UUID sessionId4 = user2Client.createSession(session4);
		

		// users 1 and 2 can access own sessions
		user1Client.getSession(sessionId1);
		user2Client.getSession(sessionId4);
		
		// admin can delete all sessions for user
		String user1IdString = launcher.getUser1Credentials().getUsername();
		String user2IdString = launcher.getUser2Credentials().getUsername();
		adminClient.deleteSessionsForUser(user1IdString, user2IdString);
		
		// no more sessions 1 and 2 for user1
		SessionResourceTest.testGetSession(404, sessionId1, user1Client);
		SessionResourceTest.testGetSession(404, sessionId2, user1Client);

		// no more sessions 3 and 4 for user2
		SessionResourceTest.testGetSession(404, sessionId3, user2Client);
		SessionResourceTest.testGetSession(404, sessionId4, user2Client);
		
		// no more sessions at all for user1
		String sessionsUser1 = adminClient.getSessionsForUser(user1IdString);
		assertEquals(sessionsUser1, "[]");

		// no more sessions at all for user2
		String sessionsUser2 = adminClient.getSessionsForUser(user2IdString);
		assertEquals(sessionsUser2, "[]");
	}
	
}
