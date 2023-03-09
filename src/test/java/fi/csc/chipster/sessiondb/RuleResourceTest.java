package fi.csc.chipster.sessiondb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.resource.RuleTable;

public class RuleResourceTest {

	private static TestServerLauncher launcher;
	private static SessionDbClient user1Client;
	private static SessionDbClient user2Client;
	private static SessionDbClient unparseableTokenClient;
	private static SessionDbClient tokenFailClient;
	private static SessionDbClient authFailClient;
	private static SessionDbClient noAuthClient;
	private static SessionDbClient sessionWorkerClient;
	private static SessionDbClient exampleSessionOwnerClient;

    @BeforeAll
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
        
		user1Client 			= new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		user2Client 			= new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);
		sessionWorkerClient		= new SessionDbClient(launcher.getServiceLocator(), launcher.getSessionWorkerToken(), Role.CLIENT);
		unparseableTokenClient 	= new SessionDbClient(launcher.getServiceLocator(), launcher.getUnparseableToken(), Role.CLIENT);
		tokenFailClient 		= new SessionDbClient(launcher.getServiceLocator(), TestServerLauncher.getWrongKeyToken(), Role.CLIENT);
		authFailClient 			= new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Credentials(), Role.CLIENT);
		noAuthClient 			= new SessionDbClient(launcher.getServiceLocator(), null, Role.CLIENT);
		
		exampleSessionOwnerClient = new SessionDbClient(
				launcher.getServiceLocator(), 
				new AuthenticationClient(launcher.getServiceLocator(), "jaas/example_session_owner", "example_session_owner", Role.CLIENT).getCredentials(),
				Role.CLIENT);
    }

    @AfterAll
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
		Rule authorization = new Rule(launcher.getUser2Credentials().getUsername(), true);    	
    	user1Client.createRule(sessionId, authorization);
    	
    	// but now she can
    	user2Client.getSession(sessionId);		
    }
	
	@Test
    public void postEveryoneRestriction() throws IOException, RestException {
		
		Session session = RestUtils.getRandomSession();		
		UUID sessionId = user1Client.createSession(session);
						
		try {
			Rule rule = new Rule(RuleTable.EVERYONE, false);    	
	    	user1Client.createRule(sessionId, rule);
	    	Assertions.fail("only example_session_owner should be able to share to everyone");
		} catch (Exception e) {			
		}
    }
	
	@Test
    public void postEveryone() throws IOException, RestException {
		
		deleteShares(exampleSessionOwnerClient);
		
		Session session = RestUtils.getRandomSession();

		UUID sessionId = exampleSessionOwnerClient.createSession(session);
				
		// user2 can't access this session
		SessionResourceTest.testGetSession(403, sessionId, user2Client);
		
		// share the session
		Rule rule = new Rule(RuleTable.EVERYONE, false);    	
    	exampleSessionOwnerClient.createRule(sessionId, rule);
    	
    	// but now she can
    	user2Client.getSession(sessionId);		
    }
	
	@Test
    public void deleteSharedSession() throws IOException, RestException {

		deleteShares(exampleSessionOwnerClient);
		
		Session session = RestUtils.getRandomSession();		

		UUID sessionId = exampleSessionOwnerClient.createSession(session);
						
		// share the session
		Rule rule = new Rule(RuleTable.EVERYONE, false);    	
    	UUID ruleId = exampleSessionOwnerClient.createRule(sessionId, rule);
    	
    	// user2 can access the session
    	user2Client.getSession(sessionId);
    	
    	// this should delete the session despite the public rule
    	exampleSessionOwnerClient.deleteRule(sessionId, ruleId);
    	    	
    	// check that it was deleted
    	SessionResourceTest.testGetSession(403, sessionId, user2Client);
    }
	
	@Test
    public void changeOwnership() throws IOException, RestException {
		
		deleteShares(user1Client);
		
		Session session = RestUtils.getRandomSession();		
		UUID sessionId = user1Client.createSession(session);
		
		UUID authorizationId1 = user1Client.getRules(sessionId).get(0).getRuleId();
		
		// user1 authorizes user2    	
    	user1Client.createRule(sessionId, launcher.getUser2Credentials().getUsername(), true);
    	    	
    	// user2 unauthorizes user1
    	user2Client.deleteRule(sessionId, authorizationId1);
    	
    	// user1 doesn't have access anymore
    	SessionResourceTest.testGetSession(403, sessionId, user1Client);
    	    	
    	// user2 can authorize another user (user1 in this case)
    	user2Client.createRule(sessionId, launcher.getUser1Credentials().getUsername(), true);
    	
    	// user1 can access again
    	user1Client.getSession(sessionId);
    }
	
	@Test
	public void getBySession() throws RestException {
		
		deleteShares(user1Client);
		
		Session session = RestUtils.getRandomSession();		
		UUID sessionId = user1Client.createSession(session);
		
		assertEquals(1, user1Client.getRules(sessionId).size());
		
		Rule authorization = new Rule(launcher.getUser2Credentials().getUsername(), true);    	
    	UUID authorizationId = user1Client.createRule(sessionId, authorization);
    	
    	assertEquals(2, user1Client.getRules(sessionId).size());
		
		user1Client.deleteRule(sessionId, authorizationId);
		
		assertEquals(1, user1Client.getRules(sessionId).size());
		
    	testGetRules(401, sessionId, unparseableTokenClient);
		testGetRules(403, sessionId, tokenFailClient);
		testGetRules(401, sessionId, authFailClient);
		testGetRules(401, sessionId, noAuthClient);
	}

	@Test
    public void get() throws IOException, RestException {		
		
		Session session = RestUtils.getRandomSession();		
		UUID sessionId = user1Client.createSession(session);				
		
		Set<Rule> rules = user1Client.getSession(sessionId).getRules();		
		assertEquals(1, rules.size());
		UUID ruleId = rules.iterator().next().getRuleId();
		
		// allowed for the owner
    	user1Client.getRule(sessionId, ruleId);
    	// not for others
    	testGetRule(403, sessionId, ruleId, user2Client);
    	
    	testGetRule(401, sessionId, ruleId, unparseableTokenClient);
		testGetRule(403, sessionId, ruleId, tokenFailClient);
		testGetRule(401, sessionId, ruleId, authFailClient);
		testGetRule(401, sessionId, ruleId, noAuthClient);
    }
	
	@Test
    public void getOtherSession() throws IOException, RestException {
		
		Session session1 = RestUtils.getRandomSession();
		Session session2 = RestUtils.getRandomSession();
		
		UUID sessionId1 = user1Client.createSession(session1);
		UUID sessionId2 = user1Client.createSession(session2);
		
		UUID datasetId2 = user1Client.createDataset(sessionId2, RestUtils.getRandomDataset());
				
		// share session1
		Rule authorization = new Rule(launcher.getUser2Credentials().getUsername(), true);    	
    	user1Client.createRule(sessionId1, authorization);
    	    	
    	// user2 must not have access to user1's other sessions 
    	SessionResourceTest.testGetSession(403, sessionId2, user2Client);    	
    	SessionDatasetResourceTest.testGetDataset(403, sessionId2, datasetId2, user2Client);
    }
	
	@Test
    public void delete() throws IOException, RestException {
		
		deleteShares(user1Client);
		
		Session session = RestUtils.getRandomSession();		
		UUID sessionId = user1Client.createSession(session);
		
		Rule authorization = new Rule(launcher.getUser2Credentials().getUsername(), true);    	
    	UUID authorizationId = user1Client.createRule(sessionId, authorization);
    	    	    	
    	testDeleteRule(401, sessionId, authorizationId, unparseableTokenClient);
		testDeleteRule(403, sessionId, authorizationId, tokenFailClient);
		testDeleteRule(401, sessionId, authorizationId, authFailClient);
		testDeleteRule(401, sessionId, authorizationId, noAuthClient);
		
		user1Client.deleteRule(sessionId, authorizationId);
    }
	
	public void deleteShares(SessionDbClient client) throws RestException {
		List<Session> shares = client.getShares();
		for (Session session : shares) {
			client.deleteSession(session.getSessionId());
		}
	}
	
	@Test
    public void acceptShare() throws IOException, RestException {
		// clear the sharing quota
		deleteShares(user1Client);
		
		Session session = RestUtils.getRandomSession();
		UUID sessionId = user1Client.createSession(session);
				
		Rule user2Rule = new Rule(launcher.getUser2Credentials().getUsername(), true);    	
    	user1Client.createRule(sessionId, user2Rule);
    	Rule user2Rule2 = new Rule(launcher.getUser2Credentials().getUsername(), true);    	
    	user1Client.createRule(sessionId, user2Rule2);
    	
    	user2Rule.setSharedBy(null);
    	// no one else can accept the share
    	testUpdateRule(403, sessionId, user2Rule, user1Client);
    	
    	// except the rule owner and session-worker
    	user2Client.updateRule(sessionId, user2Rule);    	
    	sessionWorkerClient.updateRule(sessionId, user2Rule2);
    }
	
	@Test
    public void sharingQuota() throws IOException, RestException {
		// clear the sharing quota
		deleteShares(user1Client);
		
		Session session = RestUtils.getRandomSession();
		UUID sessionId = user1Client.createSession(session);
		
		try {		
			for (int i = 0; i < 200; i++) {
				Rule user2Rule = new Rule(launcher.getUser2Credentials().getUsername(), true);    	
				user1Client.createRule(sessionId, user2Rule);
			}
			Assertions.fail();
		} catch (RestException e) {
			// quota reached
		}
    	
		// delete them
		user1Client.deleteSession(sessionId);
    }
	
	@Test
    public void putErrors() throws IOException, RestException {
		// clear the sharing quota
		deleteShares(user1Client);
		
		Session session = RestUtils.getRandomSession();
		UUID sessionId = user1Client.createSession(session);		
		
		Rule rule = user1Client.getRules(sessionId).get(0);
    	    	    	
    	testUpdateRule(401, sessionId, rule, unparseableTokenClient);
    	testUpdateRule(403, sessionId, rule, tokenFailClient);
    	testUpdateRule(401, sessionId, rule, authFailClient);
    	testUpdateRule(401, sessionId, rule, noAuthClient);
    	
    	// check that client can't change any of these (server doesn't produce errors about these at the moment)
    	Rule rule2 = new Rule(rule);
    	rule2.setRuleId(rule.getRuleId());
    	rule2.setCreated(Instant.now());
    	rule2.setReadWrite(!rule.isReadWrite());    	
    	rule2.setUsername(TestServerLauncher.UNIT_TEST_USER2);    	
    	user1Client.updateRule(sessionId, rule2);
    	Rule serverRule = user1Client.getRule(sessionId, rule.getRuleId());
    	assertEquals(rule.getCreated(), serverRule.getCreated());
    	assertEquals(rule.getSharedBy(), serverRule.getSharedBy());
    	assertEquals(rule.getUsername(), serverRule.getUsername());

    	// check that sharedBy cannot be changed
    	Rule sharedByRule = new Rule(rule);
    	sharedByRule.setSharedBy("some-other-user");
    	testUpdateRule(400, sessionId, sharedByRule, user1Client);
    	
    	Rule idRule = new Rule(rule);
    	idRule.setRuleId(RestUtils.createUUID());
    	testUpdateRule(404, sessionId, idRule, user1Client);
    	
    	user1Client.deleteSession(sessionId);
    }

	
	@Test
    public void deleteAuthorization() throws IOException, RestException {
		
		deleteShares(user1Client);
		
		Session session = RestUtils.getRandomSession();		
		UUID sessionId = user1Client.createSession(session);
		
		Rule authorization = new Rule(launcher.getUser2Credentials().getUsername(), true);    	
    	UUID authorizationId = user1Client.createRule(sessionId, authorization);
    	
    	// user2 can access the session
    	user2Client.getSession(sessionId);
    	
    	// remove the rights from the user2
		user1Client.deleteRule(sessionId, authorizationId);
		
		// user2 can't anymore access the session
		SessionResourceTest.testGetSession(403, sessionId, user2Client);
		
		// but user1 still can
		user1Client.getSession(sessionId);
    }
	
	@Test
    public void readOnly() throws IOException, RestException {
		
		deleteShares(user1Client);
		
		Session session1 = RestUtils.getRandomSession();
		Dataset dataset = RestUtils.getRandomDataset();
		Job job = RestUtils.getRandomRunningJob();
		
		UUID sessionId1 = user1Client.createSession(session1);
		UUID datasetId = user1Client.createDataset(sessionId1, dataset);
		UUID jobId = user1Client.createJob(sessionId1, job);
		
		UUID authorizationId1 = user1Client.getRules(sessionId1).get(0).getRuleId();
		
		// read authorization for user2
		Rule authorization = new Rule(launcher.getUser2Credentials().getUsername(), false);    	
    	UUID authorizationId2 = user1Client.createRule(sessionId1, authorization);
		
    	// read allowed
    	user2Client.getDataset(sessionId1, datasetId);
    	user2Client.getDatasets(sessionId1);
    	user2Client.getJob(sessionId1, jobId);
    	user2Client.getJobs(sessionId1);
    	user2Client.getSession(sessionId1);
    	user2Client.getRules(sessionId1);
    	
    	// modification forbidden
    	SessionDatasetResourceTest.testCreateDataset(403, sessionId1, RestUtils.getRandomDataset(), user2Client);
    	SessionDatasetResourceTest.testUpdateDataset(403, sessionId1, dataset, user2Client);
    	SessionDatasetResourceTest.testDeleteDataset(403, sessionId1, datasetId, user2Client);    	
    	SessionJobResourceTest.testCreateJob(403, sessionId1, RestUtils.getRandomJob(), user2Client);
    	SessionJobResourceTest.testUpdateJob(403, sessionId1, job, user2Client);
    	SessionJobResourceTest.testDeleteJob(403, sessionId1, jobId, user2Client);
    	SessionResourceTest.testUpdateSession(403, session1, user2Client);
    	SessionResourceTest.testDeleteSession(403, sessionId1, user2Client);
    	testCreateRule(403, sessionId1, new Rule("user", false), user2Client);
    	testCreateRule(403, sessionId1, new Rule("user", true), user2Client);
    	testDeleteRule(403, sessionId1, authorizationId1, user2Client);
    	
    	// removal of own rule allowed
    	user2Client.deleteRule(sessionId1, authorizationId2);
    }
	
	@Test
    public void readWrite() throws IOException, RestException {
		
		deleteShares(user1Client);
		
		Session session1 = RestUtils.getRandomSession();
		Dataset dataset = RestUtils.getRandomDataset();
		Job job = RestUtils.getRandomRunningJob();
		
		UUID sessionId1 = user1Client.createSession(session1);
		UUID datasetId = user1Client.createDataset(sessionId1, dataset);
		UUID jobId = user1Client.createJob(sessionId1, job);
		
		// read authorization for user2
		Rule authorization = new Rule(launcher.getUser2Credentials().getUsername(), true);    	
    	user1Client.createRule(sessionId1, authorization);
		
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
    	
    	user2Client.getRules(sessionId1);
    	user2Client.createRule(sessionId1, new Rule("user", false));
    	user2Client.createRule(sessionId1, new Rule("user", true));
    	
    	user2Client.getSession(sessionId1);
    	user2Client.updateSession(session1);
    	user2Client.deleteSession(sessionId1);    	
    }

	public static void testGetRule(int expected, UUID sessionId, UUID ruleId, SessionDbClient client) {
		try {
    		client.getRule(sessionId, ruleId);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	public static void testGetRules(int expected, UUID sessionId, SessionDbClient client) {
		try {
    		client.getRules(sessionId);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	public static void testCreateRule(int expected, UUID sessionId, Rule rule, SessionDbClient client) {
		try {
    		client.createRule(sessionId, rule);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	public static void testUpdateRule(int expected, UUID sessionId, Rule rule, SessionDbClient client) {
		try {
    		client.updateRule(sessionId, rule);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
	
	public static void testDeleteRule(int expected, UUID sessionId, UUID ruleId, SessionDbClient client) {
		try {
    		client.deleteRule(sessionId, ruleId);
    		assertEquals(true, false);
    	} catch (RestException e) {
    		assertEquals(expected, e.getResponse().getStatus());
    	}
	}
}
