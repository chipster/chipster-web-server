package fi.csc.chipster.sessiondb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.FileState;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.model.SessionState;

@Tag("integration")
public class SessionResourceTest {

    private static TestServerLauncher launcher;
    private static SessionDbClient user1Client;
    private static SessionDbClient user2Client;
    private static SessionDbClient schedulerClient;
    private static SessionDbClient unparseableTokenClient;
    private static SessionDbClient tokenFailClient;
    private static SessionDbClient expiredTokenClient;
    private static SessionDbClient noneTokenClient;
    private static SessionDbClient symmetricTokenClient;
    private static SessionDbClient authFailClient;
    private static SessionDbClient noAuthClient;
    private static SessionDbClient sessionWorkerClient;
    private static SessionDbClient signatureFailClient;
    private static SessionDbClient unsignedTokenClient;
    private static SessionDbAdminClient fileBrokerClient;

    @BeforeAll
    public static void setUp() throws Exception {
        Config config = new Config();
        launcher = new TestServerLauncher(config);

        user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
        user2Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);
        schedulerClient = new SessionDbClient(launcher.getServiceLocatorForAdmin(), launcher.getSchedulerToken(),
                Role.SERVER);
        unparseableTokenClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getUnparseableToken(),
                Role.CLIENT);
        tokenFailClient = new SessionDbClient(launcher.getServiceLocator(), TestServerLauncher.getWrongKeyToken(),
                Role.CLIENT);
        signatureFailClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getSignatureFailToken(),
                Role.CLIENT);
        unsignedTokenClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getUnsignedToken(),
                Role.CLIENT);
        expiredTokenClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getExpiredToken(), Role.CLIENT);
        noneTokenClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getNoneToken(), Role.CLIENT);
        symmetricTokenClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getSymmetricToken(),
                Role.CLIENT);
        // this tries to authenticate with password when token is expected
        authFailClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Credentials(), Role.CLIENT);
        noAuthClient = new SessionDbClient(launcher.getServiceLocator(), null, Role.CLIENT);
        sessionWorkerClient = new SessionDbClient(launcher.getServiceLocator(), launcher.getSessionWorkerToken(),
                Role.CLIENT);
        // file-broker's role can list the files of a storage
        fileBrokerClient = new SessionDbAdminClient(launcher.getServiceLocatorForScheduler(),
                launcher.getFileBrokerToken());
    }

    @AfterAll
    public static void tearDown() throws Exception {
        launcher.stop();
    }

    @Test
    public void post() throws IOException, RestException {

        user1Client.createSession(RestUtils.getRandomSession());

        testCreateSession(401, unparseableTokenClient);
        testCreateSession(403, tokenFailClient);
        testCreateSession(401, authFailClient);
        testCreateSession(401, noAuthClient);
    }

    public static void testCreateSession(int expected, SessionDbClient client) {
        try {
            client.createSession(RestUtils.getRandomSession());
            assertEquals(true, false);
        } catch (RestException e) {
            assertEquals(expected, e.getResponse().getStatus());
        }
    }

    @Test
    public void get() throws IOException, RestException {

        Session session1 = RestUtils.getRandomSession();
        Session session2 = RestUtils.getRandomSession();

        UUID sessionId1 = user1Client.createSession(session1);
        UUID sessionId2 = user2Client.createSession(session2);

        Session getSession1 = user1Client.getSession(sessionId1);

        assertEquals(sessionId1, getSession1.getSessionId());
        assertEquals(session1.getName(), getSession1.getName());
        // check that user2Client works, other tests rely on it
        assertEquals(true, user2Client.getSession(sessionId2) != null);
        // servers can read any session
        assertEquals(true, schedulerClient.getSession(sessionId1) != null);
        assertEquals(true, sessionWorkerClient.getSession(sessionId1) != null);

        // wrong user
        testGetSession(403, sessionId2, user1Client);
        testGetSession(403, sessionId1, user2Client);

        // auth tests
        testGetSession(401, sessionId1, unparseableTokenClient);
        testGetSession(403, sessionId1, tokenFailClient);
        testGetSession(403, sessionId1, signatureFailClient);
        testGetSession(401, sessionId1, unsignedTokenClient);
        testGetSession(403, sessionId1, expiredTokenClient);
        testGetSession(401, sessionId1, noneTokenClient);
        testGetSession(401, sessionId1, symmetricTokenClient);
        testGetSession(401, sessionId1, authFailClient);
        testGetSession(401, sessionId1, noAuthClient);
    }

    @Test
    public void getStats() throws IOException, RestException {

        // get total size in the beginning
        long size1 = user1Client.getStats().getSize();

        // use large value to check that json conversions don't use int
        long fileSize = Integer.MAX_VALUE * 1024l;

        // add a file
        Session session1 = RestUtils.getRandomSession();
        UUID sessionId1 = user1Client.createSession(session1);
        Dataset dataset1 = RestUtils.getRandomDataset();
        dataset1.setDatasetIdPair(null);
        File file1 = new File();
        file1.setFileId(RestUtils.createUUID());
        file1.setSize(fileSize);
        dataset1.setFile(file1);
        user1Client.createDataset(sessionId1, dataset1);

        // get new total size
        long size2 = user1Client.getStats().getSize();

        // check that the total size has grown with the file size
        assertEquals(size1 + fileSize, size2);

        user1Client.deleteSession(sessionId1);

        // auth tests
        testGetStats(401, unparseableTokenClient);
        testGetStats(403, tokenFailClient);
        testGetStats(403, signatureFailClient);
        testGetStats(401, unsignedTokenClient);
        testGetStats(403, expiredTokenClient);
        testGetStats(401, noneTokenClient);
        testGetStats(401, symmetricTokenClient);
        testGetStats(401, authFailClient);
        testGetStats(401, noAuthClient);
    }

    @Test
    public void getSharesErrors() throws IOException, RestException {

        // auth tests
        testGetShares(401, unparseableTokenClient);
        testGetShares(403, tokenFailClient);
        testGetShares(401, authFailClient);
        testGetShares(401, noAuthClient);
    }

    public static void testGetSession(int expected, UUID id, SessionDbClient client) {
        try {
            client.getSession(id);
            assertEquals(true, false);
        } catch (RestException e) {
            assertEquals(expected, e.getResponse().getStatus());
        }
    }

    public static void testGetStats(int expected, SessionDbClient client) {
        try {
            client.getStats();
            assertEquals(true, false);
        } catch (RestException e) {
            assertEquals(expected, e.getResponse().getStatus());
        }
    }

    public static void testGetShares(int expected, SessionDbClient client) {
        try {
            client.getShares();
            assertEquals(true, false);
        } catch (RestException e) {
            assertEquals(expected, e.getResponse().getStatus());
        }
    }

    @Test
    public void getAll() throws RestException {

        Session session1 = RestUtils.getRandomSession();
        Session session2 = RestUtils.getRandomSession();

        UUID sessionId1 = user1Client.createSession(session1);
        UUID sessionId2 = user1Client.createSession(session2);

        assertEquals(true, user1Client.getSessions().containsKey(sessionId1));
        assertEquals(true, user1Client.getSessions().containsKey(sessionId2));

        // wrong user
        assertEquals(false, user2Client.getSessions().containsKey(sessionId1));

        // auth tests
        testGetSessions(401, unparseableTokenClient);
        testGetSessions(403, tokenFailClient);
        testGetSessions(401, authFailClient);
        testGetSessions(401, noAuthClient);
    }

    @Test
    public void getAllByUserId() throws RestException {

        String userIdString = launcher.getUser1Credentials().getUsername();

        // session-worker can get the sessions of others
        sessionWorkerClient.getSessions(userIdString);

        testGetSessions(403, userIdString, user1Client);
        testGetSessions(401, userIdString, unparseableTokenClient);
        testGetSessions(403, userIdString, tokenFailClient);
        testGetSessions(401, userIdString, authFailClient);
        testGetSessions(401, userIdString, noAuthClient);
    }

    private void testGetSessions(int expected, SessionDbClient client) {
        try {
            client.getSessions();
            assertEquals(true, false);
        } catch (RestException e) {
            assertEquals(expected, e.getResponse().getStatus());
        }
    }

    private void testGetSessions(int expected, String userIdString, SessionDbClient client) {
        try {
            client.getSessions(userIdString);
            assertEquals(true, false);
        } catch (RestException e) {
            assertEquals(expected, e.getResponse().getStatus());
        }
    }

    @Test
    public void put() throws RestException {

        Session session1 = RestUtils.getRandomSession();
        UUID sessionId1 = user1Client.createSession(session1);

        session1.setName("new name");
        user1Client.updateSession(session1);
        assertEquals("new name", user1Client.getSession(sessionId1).getName());

        // servers can modify any session
        session1.setName("new name2");
        schedulerClient.updateSession(session1);
        assertEquals("new name2", user1Client.getSession(sessionId1).getName());

        session1.setName("new name3");
        sessionWorkerClient.updateSession(session1);
        assertEquals("new name3", user1Client.getSession(sessionId1).getName());

        // wrong user
        testUpdateSession(403, session1, user2Client);

        testUpdateSession(401, session1, unparseableTokenClient);
        testUpdateSession(403, session1, tokenFailClient);
        testUpdateSession(401, session1, authFailClient);
        testUpdateSession(401, session1, noAuthClient);
    }

    @Test
    public void sessionState() throws RestException {

        Session session1 = RestUtils.getRandomSession();
        session1.setState(SessionState.IMPORT);
        UUID sessionId = user1Client.createSession(session1);

        assertEquals(SessionState.IMPORT, user1Client.getSession(sessionId).getState());

        // we can modify the session in IMPORT state
        session1.setName("new name");
        user1Client.updateSession(session1);

        // and it should be still stay in IMPORt
        assertEquals(SessionState.IMPORT, user1Client.getSession(sessionId).getState());

        // change session to TEMPORARY
        session1.setState(SessionState.TEMPORARY_UNMODIFIED);
        user1Client.updateSession(session1);

        // check that change succeeded
        assertEquals(SessionState.TEMPORARY_UNMODIFIED, user1Client.getSession(sessionId).getState());

        // modify a TEMPORARY session
        session1.setName("new name 2");
        user1Client.updateSession(session1);

        // and the state should have changed to READY
        assertEquals(SessionState.TEMPORARY_MODIFIED, user1Client.getSession(sessionId).getState());

        user1Client.deleteSession(sessionId);
    }

    public static void testUpdateSession(int expected, Session newSession, SessionDbClient client) {
        try {
            client.updateSession(newSession);
            assertEquals(true, false);
        } catch (RestException e) {
            assertEquals(expected, e.getResponse().getStatus());
        }
    }

    @Test
    public void delete() throws RestException {

        Session session1 = RestUtils.getRandomSession();
        UUID sessionId1 = user1Client.createSession(session1);

        // wrong user
        testDeleteSession(403, sessionId1, user2Client);

        // auth errors
        testDeleteSession(401, sessionId1, unparseableTokenClient);
        testDeleteSession(403, sessionId1, tokenFailClient);
        testDeleteSession(401, sessionId1, authFailClient);
        testDeleteSession(401, sessionId1, noAuthClient);

        // delete
        user1Client.deleteSession(sessionId1);

        // doesn't exist anymore
        testGetSession(404, sessionId1, user1Client);
    }

    public static void testDeleteSession(int expected, UUID sessionId, SessionDbClient client) {
        try {
            client.deleteSession(sessionId);
            assertEquals(true, false);
        } catch (RestException e) {
            assertEquals(expected, e.getResponse().getStatus());
        }
    }

    /**
     * Deleting a session deletes the files that only its datasets use, also when two of its datasets
     * have the same file, and keeps the files that datasets of other sessions use.
     */
    @Test
    public void deleteFiles() throws RestException {

        // a storage of its own, so that the files of this test can be listed
        String storageId = "test-" + RestUtils.createUUID();

        UUID sessionId1 = user1Client.createSession(RestUtils.getRandomSession());
        UUID sessionId2 = user1Client.createSession(RestUtils.getRandomSession());

        UUID onlyHere = createDatasetWithFile(sessionId1, getRandomFile(storageId));
        UUID shared = createDatasetWithFile(sessionId1, getRandomFile(storageId));
        UUID twice = createDatasetWithFile(sessionId1, getRandomFile(storageId));

        File onlyHereFile = user1Client.getDataset(sessionId1, onlyHere).getFile();
        File sharedFile = user1Client.getDataset(sessionId1, shared).getFile();
        File twiceFile = user1Client.getDataset(sessionId1, twice).getFile();

        // the same file in another dataset of the same session, and in a dataset of another session
        createDatasetWithFile(sessionId1, twiceFile);
        UUID otherSessionDataset = createDatasetWithFile(sessionId2, sharedFile);

        assertEquals(Set.of(onlyHereFile.getFileId(), sharedFile.getFileId(), twiceFile.getFileId()),
                getFileIds(storageId));

        user1Client.deleteSession(sessionId1);

        // only the file of the other session is left, and its dataset still has it
        assertEquals(Set.of(sharedFile.getFileId()), getFileIds(storageId));
        assertEquals(sharedFile.getFileId(),
                user1Client.getDataset(sessionId2, otherSessionDataset).getFile().getFileId());

        user1Client.deleteSession(sessionId2);

        assertEquals(Set.of(), getFileIds(storageId));
    }

    /**
     * Delete a session with more files than fit in one delete statement
     */
    @Test
    public void deleteManyFiles() throws RestException {

        String storageId = "test-" + RestUtils.createUUID();
        UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());

        int count = 2500;
        Set<UUID> fileIds = new HashSet<>();
        for (int i = 0; i < count; i++) {
            File file = getRandomFile(storageId);
            fileIds.add(file.getFileId());
            createDatasetWithFile(sessionId, file);
        }
        assertEquals(fileIds, getFileIds(storageId));

        long t = System.currentTimeMillis();
        user1Client.deleteSession(sessionId);
        System.out.println("deleted a session of " + count + " datasets and files in "
                + (System.currentTimeMillis() - t) + " ms");

        assertEquals(Set.of(), getFileIds(storageId));
        testGetSession(404, sessionId, user1Client);
    }

    /**
     * Only the deletion sets the state DELETE, because it refuses all writes to the session
     */
    @Test
    public void putDeleteState() throws RestException {

        Session newSession = RestUtils.getRandomSession();
        newSession.setState(SessionState.DELETE);
        assertStatus(400, () -> user1Client.createSession(newSession));

        UUID sessionId = user1Client.createSession(RestUtils.getRandomSession());

        Session session = user1Client.getSession(sessionId);
        session.setState(SessionState.DELETE);
        assertStatus(400, () -> user1Client.updateSession(session));

        // the session can still be modified and deleted
        user1Client.createDataset(sessionId, RestUtils.getRandomDataset());
        user1Client.deleteSession(sessionId);
        testGetSession(404, sessionId, user1Client);
    }

    private interface RestCall {
        void run() throws RestException;
    }

    private static void assertStatus(int expected, RestCall call) {
        try {
            call.run();
            assertEquals(true, false, "expected " + expected);
        } catch (RestException e) {
            assertEquals(expected, e.getResponse().getStatus());
        }
    }

    private static File getRandomFile(String storageId) {
        File file = new File();
        file.setFileId(RestUtils.createUUID());
        file.setSize(1);
        file.setStorage(storageId);
        file.setState(FileState.COMPLETE);
        return file;
    }

    private static UUID createDatasetWithFile(UUID sessionId, File file) throws RestException {
        Dataset dataset = RestUtils.getRandomDataset();
        dataset.setFile(file);
        return user1Client.createDataset(sessionId, dataset);
    }

    private static Set<UUID> getFileIds(String storageId) throws RestException {
        return fileBrokerClient.getFiles(storageId, null).stream().map(File::getFileId).collect(Collectors.toSet());
    }
}
