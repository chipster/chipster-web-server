package fi.csc.chipster.sessionworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionResourceTest;
import fi.csc.chipster.sessiondb.model.Rule;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Each run sends four support requests as user1 and three as user2, which
 * session-worker throttles per user (session-worker-support-throttle-request-count
 * in 24 hours by default), so restart the backend if the tests start to get 429
 * responses.
 */
public class SupportResourceTest {

    private static TestServerLauncher launcher;

    private static SessionDbClient user1Client;
    private static SessionDbClient user2Client;
    private static SessionDbClient sessionWorkerClient;

    private static String user1;
    private static String user2;
    private static String supportSessionOwner;

    @BeforeAll
    public static void setUp() throws Exception {
        Config config = new Config();
        launcher = new TestServerLauncher(config);

        user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
        user2Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);
        sessionWorkerClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getSessionWorkerToken(),
                Role.CLIENT);

        user1 = launcher.getUser1Credentials().getUsername();
        user2 = launcher.getUser2Credentials().getUsername();
        supportSessionOwner = config.getString(Config.KEY_SESSION_WORKER_SUPPORT_SESSION_OWNER);
    }

    @AfterAll
    public static void tearDown() throws Exception {
        launcher.stop();
    }

    @Test
    public void supportCopy() throws RestException {

        // the client copies the session and shares the copy to the support account
        UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
        user1Client.createRule(sessionId, new Rule(supportSessionOwner, true));

        assertEquals(204, postSupportRequest(launcher.getUser1Target(Role.SESSION_WORKER), sessionId));

        // the copy is hidden from the user
        SessionResourceTest.testGetSession(403, sessionId, user1Client);

        // and the support account has accepted the share
        Set<Rule> rules = sessionWorkerClient.getSession(sessionId).getRules();
        assertEquals(1, rules.size());
        Rule shareRule = rules.iterator().next();
        assertEquals(supportSessionOwner, shareRule.getUsername());
        assertNull(shareRule.getSharedBy());
    }

    @Test
    public void otherRules() throws RestException {

        // only the copy's two rules are accepted
        UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
        user1Client.createRule(sessionId, new Rule(user2, true));
        user2Client.createRule(sessionId, new Rule(supportSessionOwner, true));

        assertEquals(403, postSupportRequest(launcher.getUser2Target(Role.SESSION_WORKER), sessionId));

        // rules weren't changed
        assertEquals(Set.of(user1, user2, supportSessionOwner), getUsernames(sessionId));
        user1Client.getSession(sessionId);
        user2Client.getSession(sessionId);
    }

    @Test
    public void notSharedToSupport() throws RestException {

        UUID sessionId = user2Client.createSession(RestUtils.getRandomSession());
        user2Client.createRule(sessionId, new Rule(user1, true));

        assertEquals(403, postSupportRequest(launcher.getUser2Target(Role.SESSION_WORKER), sessionId));

        assertEquals(Set.of(user1, user2), getUsernames(sessionId));
    }

    @Test
    public void readOnlyShare() throws RestException {

        UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
        user1Client.createRule(sessionId, new Rule(supportSessionOwner, false));

        assertEquals(403, postSupportRequest(launcher.getUser1Target(Role.SESSION_WORKER), sessionId));

        assertEquals(Set.of(user1, supportSessionOwner), getUsernames(sessionId));
    }

    @Test
    public void pendingShare() throws RestException {

        // the user's own rule is a share that the user hasn't accepted
        UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());
        user1Client.createRule(sessionId, new Rule(user2, true));
        user2Client.createRule(sessionId, new Rule(supportSessionOwner, true));
        deleteRule(sessionId, user1);

        assertEquals(403, postSupportRequest(launcher.getUser2Target(Role.SESSION_WORKER), sessionId));

        assertEquals(Set.of(user2, supportSessionOwner), getUsernames(sessionId));
    }

    @Test
    public void invalidSessionUrl() {
        WebTarget target = launcher.getUser1Target(Role.SESSION_WORKER);
        assertEquals(400, postSupportRequest(target, "https://example.com/analyze/not-a-session-id", true));
    }

    @Test
    public void noMail() {
        // the test users have no email address in auth either, so there is no
        // reply-to address at all
        WebTarget target = launcher.getUser1Target(Role.SESSION_WORKER);
        assertEquals(204, postSupportRequest(target, null, false));
    }

    private void deleteRule(UUID sessionId, String username) throws RestException {
        for (Rule rule : sessionWorkerClient.getRules(sessionId)) {
            if (username.equals(rule.getUsername())) {
                sessionWorkerClient.deleteRule(sessionId, rule.getRuleId());
            }
        }
    }

    private Set<String> getUsernames(UUID sessionId) throws RestException {
        return sessionWorkerClient.getSession(sessionId).getRules().stream()
                .map(r -> r.getUsername())
                .collect(Collectors.toSet());
    }

    private static int postSupportRequest(WebTarget sessionWorkerTarget, UUID sessionId) {
        return postSupportRequest(sessionWorkerTarget, "https://example.com/analyze/" + sessionId, true);
    }

    private static int postSupportRequest(WebTarget sessionWorkerTarget, String sessionUrl, boolean withMail) {
        SupportRequest request = new SupportRequest();
        request.setMessage("test message");
        if (withMail) {
            // the web app requires it
            request.setMail("test@example.com");
        }
        request.setSession(sessionUrl);

        try (Response response = sessionWorkerTarget.path("support").path("request").request()
                .post(Entity.entity(request, MediaType.APPLICATION_JSON))) {
            return response.getStatus();
        }
    }
}
