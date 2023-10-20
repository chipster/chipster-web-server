package fi.csc.chipster.sessiondb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
		
		String result = adminClient.getSessionsForUser(user1IdString);
		
//		// json example
//		ObjectMapper mapper = new ObjectMapper();
//		List<HashMap<String, Object>> resultMaps = mapper.readValue(result,
//                new TypeReference<List<HashMap<String, Object>>>(){});
//		for (Map<String, Object> m: resultMaps) {
//			System.out.println(m.get("name"));
//		}
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
	
	
}
