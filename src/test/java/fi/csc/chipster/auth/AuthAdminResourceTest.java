package fi.csc.chipster.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import jakarta.ws.rs.client.Client;

public class AuthAdminResourceTest {

	private static TestServerLauncher launcher;
	
	private static UserId user1Id;		
	private static Client user1Client;
	private static User user1;

	private static UserId user2Id;		
	private static Client user2Client;
	private static User user2;

	private static AuthenticationAdminClient adminClient;
	
    @BeforeEach
    public void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
            
    	// login users to add it to the User table
    	new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
    	user1Client = launcher.getUser1Client();
		user1Id = new UserId(launcher.getUser1Credentials().getUsername());
		user1 = AuthenticationClient.getUser(user1Id, user1Client, launcher.getServiceLocator());
		
		new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);
		user2Client = launcher.getUser2Client();
		user2Id = new UserId(launcher.getUser2Credentials().getUsername());		
		
		user2 = AuthenticationClient.getUser(user2Id, user2Client, launcher.getServiceLocator());
		
		adminClient = new AuthenticationAdminClient(launcher.getServiceLocatorForAdmin(), launcher.getAdminToken());
    }

    @AfterEach
    public void tearDown() throws Exception {
    	try {
    		adminClient.deleteUser(user1Id.toUserIdString());
    		adminClient.deleteUser(user2Id.toUserIdString());
    	} catch (Exception e) {
    		
    	}
    	launcher.stop();
    }
	
	@Test
    public void deleteUser() throws IOException, RestException {

		// delete user
		String json = adminClient.deleteUser(user1Id.toUserIdString());
		assertEquals(RestUtils.asJson(new String[] {user1Id.toUserIdString()}), json);
		
		// expect not found for deleted user
		testGetUser(404, user1Id, user1Client);

		// try deleting already deleted
		try {
			adminClient.deleteUser(user1Id.toUserIdString());
			assertEquals(true, false, "delete alread existing user should have failed");
		} catch (RestException e) {
			assertEquals(404, e.getResponse().getStatus());
		}
	}

	@Test
    public void deleteMultipleUsers() throws IOException, RestException {
		
		// delete users
		String json = adminClient.deleteUser(user1Id.toUserIdString(), user2Id.toUserIdString());
		assertEquals(RestUtils.asJson(new String[] {user1Id.toUserIdString(), user2Id.toUserIdString()}), json);
				
		// expect not found for deleted user
		testGetUser(404, user1Id, user1Client);
		testGetUser(404, user2Id, user2Client);

		// try deleting already deleted
		try {
			adminClient.deleteUser(user1Id.toUserIdString());
			assertEquals(true, false, "delete already deleted user should have failed");
		} catch (RestException e) {
			assertEquals(404, e.getResponse().getStatus());
		}
	}

	@Test
    public void deleteMultipleFail() throws IOException, RestException {

		// delete user1
		adminClient.deleteUser(user1Id.toUserIdString());
		
		// delete users, user2 first, user1 should fail
		try {
			adminClient.deleteUser(user2Id.toUserIdString(), user1Id.toUserIdString());
			assertEquals(true, false, "delete already deleted user should have failed");
		} catch (RestException e) {
			
			// should get 404 and the missing userId 
			String json = e.getResponse().readEntity(String.class);
			assertEquals(RestUtils.asJson(new String[] {user1Id.toUserIdString()}), json);
			
			assertEquals(404, e.getResponse().getStatus());
		}

		// since delete fail, user2 delete should be rollbacked and user2 still there
		testGetUser(404, user1Id, user1Client);
		AuthenticationClient.getUser(user2Id, user2Client, launcher.getServiceLocator());
		
	}
	
	public static void testGetUser(int expected, UserId userId, Client client) {
		try {
			AuthenticationClient.getUser(userId, client, launcher.getServiceLocator());
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}

}
