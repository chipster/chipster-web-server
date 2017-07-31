package fi.csc.chipster.sessiondb;

import java.io.IOException;
import java.util.UUID;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonParseException;

import static org.junit.Assert.assertEquals;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Authorization;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Session;

public class AuthorizationResourceTest {

	private static TestServerLauncher launcher;
	private static SessionDbClient user1Client;
	private static SessionDbClient user2Client;
	private static SessionDbClient sessionDbClient;
	private static SessionDbClient unparseableTokenClient;
	private static SessionDbClient tokenFailClient;
	private static SessionDbClient authFailClient;
	private static SessionDbClient noAuthClient;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
        
		user1Client 			= new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token());
		user2Client 			= new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token());
		sessionDbClient 		= new SessionDbClient(launcher.getServiceLocator(), launcher.getSessionDbToken());
		unparseableTokenClient 	= new SessionDbClient(launcher.getServiceLocator(), launcher.getUnparseableToken());
		tokenFailClient 		= new SessionDbClient(launcher.getServiceLocator(), launcher.getWrongToken());
		authFailClient 			= new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Credentials());
		noAuthClient 			= new SessionDbClient(launcher.getServiceLocator(), null);
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }
    
	@Test
    public void post() throws IOException, RestException {
		
		Session session = RestUtils.getRandomSession();		
		UUID sessionId = user1Client.createSession(session);
				
		// user2 can't access this session
		SessionResourceTest.testGetSession(403, sessionId, user2Client);
		
		// share the session
		Authorization authorization = new Authorization(launcher.getUser2Credentials().getUsername(), session, true);    	
    	user1Client.createAuthorization(authorization);
    	
    	// but now she can
    	user2Client.getSession(sessionId);		
    }
	
	@Test
    public void changeOwnership() throws IOException, RestException {
		
		Session session = RestUtils.getRandomSession();		
		UUID sessionId = user1Client.createSession(session);
		
		UUID authorizationId1 = user1Client.getAuthorizations(sessionId).get(0).getAuthorizationId();
		
		// user1 authorizes user2    	
    	user1Client.createAuthorization(launcher.getUser2Credentials().getUsername(), session, true);
    	    	
    	// user2 unauthorizes user1
    	user2Client.deleteAuthorization(authorizationId1);
    	
    	// user1 doesn't have access anymore
    	SessionResourceTest.testGetSession(403, sessionId, user1Client);
    	    	
    	// user2 can authorize another user (user1 in this case)
    	user2Client.createAuthorization(launcher.getUser1Credentials().getUsername(), session, true);
    	
    	// user1 can access again
    	user1Client.getSession(sessionId);
    }
	
	@Test
	public void getBySession() throws RestException {
		
		Session session = RestUtils.getRandomSession();		
		UUID sessionId = user1Client.createSession(session);
		
		assertEquals(1, user1Client.getAuthorizations(sessionId).size());
		
		Authorization authorization = new Authorization(launcher.getUser2Credentials().getUsername(), session, true);    	
    	UUID authorizationId = user1Client.createAuthorization(authorization);
    	
    	assertEquals(2, user1Client.getAuthorizations(sessionId).size());
		
		user1Client.deleteAuthorization(authorizationId);
		
		assertEquals(1, user1Client.getAuthorizations(sessionId).size());
		
    	testGetAuthorizations(401, sessionId, unparseableTokenClient);
		testGetAuthorizations(403, sessionId, tokenFailClient);
		testGetAuthorizations(401, sessionId, authFailClient);
		testGetAuthorizations(401, sessionId, noAuthClient);
	}
	
	@Test
	public void getAll() throws RestException, JsonParseException, IOException {
		// only allowed for sessionDb
		sessionDbClient.getAuthorizations();
		
		testGetAuthorizationsAll(403, user1Client);
    	testGetAuthorizationsAll(401, unparseableTokenClient);
		testGetAuthorizationsAll(403, tokenFailClient);
		testGetAuthorizationsAll(401, authFailClient);
		testGetAuthorizationsAll(401, noAuthClient);
	}


	@Test
    public void get() throws IOException, RestException {		
		
		Session session = RestUtils.getRandomSession();		
		user1Client.createSession(session);		
		Authorization authorization = new Authorization(launcher.getUser2Credentials().getUsername(), session, true);    	
    	UUID authorizationId = user1Client.createAuthorization(authorization);
    	    			
		// only allowed for sessionDb
    	sessionDbClient.getAuthorization(authorizationId);
    	
		testGetAuthorization(403, authorizationId, user1Client);
    	testGetAuthorization(401, authorizationId, unparseableTokenClient);
		testGetAuthorization(403, authorizationId, tokenFailClient);
		testGetAuthorization(401, authorizationId, authFailClient);
		testGetAuthorization(401, authorizationId, noAuthClient);
    }
	
	@Test
    public void getOtherSession() throws IOException, RestException {
		
		Session session1 = RestUtils.getRandomSession();
		Session session2 = RestUtils.getRandomSession();
		
		@SuppressWarnings("unused")
		UUID sessionId1 = user1Client.createSession(session1);
		UUID sessionId2 = user1Client.createSession(session2);
		
		UUID datasetId2 = user1Client.createDataset(sessionId2, RestUtils.getRandomDataset());
				
		// share session1
		Authorization authorization = new Authorization(launcher.getUser2Credentials().getUsername(), session1, true);    	
    	user1Client.createAuthorization(authorization);
    	    	
    	// user2 must not have access to user1's other sessions 
    	SessionResourceTest.testGetSession(403, sessionId2, user2Client);    	
    	DatasetResourceTest.testGetDataset(403, sessionId2, datasetId2, user2Client);
    }
	
	@Test
    public void delete() throws IOException, RestException {
		
		Session session = RestUtils.getRandomSession();		
		user1Client.createSession(session);
		
		Authorization authorization = new Authorization(launcher.getUser2Credentials().getUsername(), session, true);    	
    	UUID authorizationId = user1Client.createAuthorization(authorization);
    	    	    	
    	testDeleteAuthorization(401, authorizationId, unparseableTokenClient);
		testDeleteAuthorization(403, authorizationId, tokenFailClient);
		testDeleteAuthorization(401, authorizationId, authFailClient);
		testDeleteAuthorization(401, authorizationId, noAuthClient);
		
		user1Client.deleteAuthorization(authorizationId);
    }
	
	@Test
    public void deleteAuthorization() throws IOException, RestException {
		
		Session session = RestUtils.getRandomSession();		
		UUID sessionId = user1Client.createSession(session);
		
		Authorization authorization = new Authorization(launcher.getUser2Credentials().getUsername(), session, true);    	
    	UUID authorizationId = user1Client.createAuthorization(authorization);
    	
    	// user2 can access the session
    	user2Client.getSession(sessionId);
    	
    	// remove the rights from the user2
		user1Client.deleteAuthorization(authorizationId);
		
		// user2 can't anymore access the session
		SessionResourceTest.testGetSession(403, sessionId, user2Client);
		
		// but user1 still can
		user1Client.getSession(sessionId);
    }
	
	@Test
    public void readOnly() throws IOException, RestException {
		
		Session session1 = RestUtils.getRandomSession();
		Dataset dataset = RestUtils.getRandomDataset();
		Job job = RestUtils.getRandomCompletedJob();
		
		UUID sessionId1 = user1Client.createSession(session1);
		UUID datasetId = user1Client.createDataset(sessionId1, dataset);
		UUID jobId = user1Client.createJob(sessionId1, job);
		
		// read authorization for user2
		Authorization authorization = new Authorization(launcher.getUser2Credentials().getUsername(), session1, false);    	
    	UUID authorizationId = user1Client.createAuthorization(authorization);
		
    	// read allowed
    	user2Client.getDataset(sessionId1, datasetId);
    	user2Client.getDatasets(sessionId1);
    	user2Client.getJob(sessionId1, jobId);
    	user2Client.getJobs(sessionId1);
    	user2Client.getSession(sessionId1);
    	user2Client.getAuthorizations(sessionId1);
    	
    	// modification forbidden
    	DatasetResourceTest.testCreateDataset(403, sessionId1, RestUtils.getRandomDataset(), user2Client);
    	DatasetResourceTest.testUpdateDataset(403, sessionId1, dataset, user2Client);
    	DatasetResourceTest.testDeleteDataset(403, sessionId1, datasetId, user2Client);    	
    	JobResourceTest.testCreateJob(403, sessionId1, RestUtils.getRandomJob(), user2Client);
    	JobResourceTest.testUpdateJob(403, sessionId1, job, user2Client);
    	JobResourceTest.testDeleteJob(403, sessionId1, jobId, user2Client);
    	SessionResourceTest.testUpdateSession(403, session1, user2Client);
    	SessionResourceTest.testDeleteSession(403, sessionId1, user2Client);
    	testCreateAuthorization(403, new Authorization("user", session1, false), user2Client);
    	testCreateAuthorization(403, new Authorization("user", session1, true), user2Client);
    	testDeleteAuthorization(403, authorizationId, user2Client);
    }
	
	@Test
    public void readWrite() throws IOException, RestException {
		
		Session session1 = RestUtils.getRandomSession();
		Dataset dataset = RestUtils.getRandomDataset();
		Job job = RestUtils.getRandomCompletedJob();
		
		UUID sessionId1 = user1Client.createSession(session1);
		UUID datasetId = user1Client.createDataset(sessionId1, dataset);
		UUID jobId = user1Client.createJob(sessionId1, job);
		
		// read authorization for user2
		Authorization authorization = new Authorization(launcher.getUser2Credentials().getUsername(), session1, true);    	
    	user1Client.createAuthorization(authorization);
		
    	user2Client.getDataset(sessionId1, datasetId);
    	user2Client.getDatasets(sessionId1);
    	user2Client.createDataset(sessionId1, RestUtils.getRandomDataset());
    	user2Client.updateDataset(sessionId1, dataset);
    	user2Client.deleteDataset(sessionId1, datasetId);
    	
    	user2Client.getJob(sessionId1, jobId);
    	user2Client.getJobs(sessionId1);
    	user2Client.createJob(sessionId1, RestUtils.getRandomJob());
    	user2Client.updateJob(sessionId1, job);
    	user2Client.deleteJob(sessionId1, jobId);
    	
    	user2Client.getAuthorizations(sessionId1);
    	user2Client.createAuthorization(new Authorization("user", session1, false));
    	user2Client.createAuthorization(new Authorization("user", session1, true));
    	
    	user2Client.getSession(sessionId1);
    	user2Client.updateSession(session1);
    	user2Client.deleteSession(sessionId1);    	
    }

	public static void testGetAuthorization(int expected, UUID authorizationId, SessionDbClient client) {
		try {
    		client.getAuthorization(authorizationId);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	public static void testGetAuthorizations(int expected, UUID sessionId, SessionDbClient client) {
		try {
    		client.getAuthorizations(sessionId);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	public static void testGetAuthorizationsAll(int expected, SessionDbClient client) throws JsonParseException, IOException {
		try {
    		client.getAuthorizations();
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	public static void testCreateAuthorization(int expected, Authorization authorization, SessionDbClient client) {
		try {
    		client.createAuthorization(authorization);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	public static void testDeleteAuthorization(int expected, UUID authorizationId, SessionDbClient client) {
		try {
    		client.deleteAuthorization(authorizationId);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
}