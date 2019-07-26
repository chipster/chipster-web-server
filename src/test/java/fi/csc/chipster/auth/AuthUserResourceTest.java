package fi.csc.chipster.auth;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.List;

import javax.ws.rs.client.Client;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;

public class AuthUserResourceTest {

	private static TestServerLauncher launcher;
	
    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
            
    	// login two users to add them to the User table
		new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }
	
	@Test
    public void get() throws IOException, RestException {
		UserId userId = new UserId(launcher.getUser1Credentials().getUsername());		
		User user = AuthenticationClient.getUser(userId, launcher.getUser1Client(), launcher.getServiceLocator());
		
		assertEquals(userId.toUserIdString(), user.getUserId().toUserIdString());
		// we don't have proper LDAP queries yet, so the name is simply the username 
		assertEquals(userId.getUsername(), user.getName());
				
		// wrong user
		testGetUser(403, userId, launcher.getUser2Client());
		
		// auth tests
		testGetUser(401, userId, launcher.getUnparseableTokenClient());
		testGetUser(403, userId, launcher.getWrongTokenClient());		
		testGetUser(403, userId, launcher.getNoAuthClient());
		// launcher.getAuthFailClient() would pass, because AuthenticationService allows password logins
    }
	
	public static void testGetUser(int expected, UserId userId, Client client) {
		try {
			AuthenticationClient.getUser(userId, client, launcher.getServiceLocator());
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	public static void testGetUsers(int expected, Client client) {
		try {
			AuthenticationClient.getUsers(client, launcher.getServiceLocator());
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	@Test
    public void getAll() throws RestException {
		
		// admin can get all
		List<User> users = AuthenticationClient.getUsers(launcher.getAdminClient(), launcher.getServiceLocator());
		
		assertEquals(false, users.isEmpty());
				
		// not allowed for normal users
		testGetUsers(403, launcher.getUser1Client());
		
		// auth tests
		testGetUsers(401, launcher.getUnparseableTokenClient());
		testGetUsers(403, launcher.getWrongTokenClient());
		testGetUsers(403, launcher.getAuthFailClient());
		testGetUsers(403, launcher.getNoAuthClient());
    }
}
