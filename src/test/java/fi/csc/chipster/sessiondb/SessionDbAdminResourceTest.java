package fi.csc.chipster.sessiondb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filebroker.FileResourceTest;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.FileState;
import fi.csc.chipster.sessiondb.model.Session;
import jakarta.ws.rs.client.WebTarget;

public class SessionDbAdminResourceTest {

    private static TestServerLauncher launcher;
    private static SessionDbAdminClient adminClient;
    private static SessionDbClient user1Client;
    private static SessionDbClient user2Client;
    private static SessionDbAdminClient sessionDbClientForFileBroker;
    private static WebTarget fileBrokerTarget1;

    @BeforeAll
    public static void setUp() throws Exception {
        Config config = new Config();
        launcher = new TestServerLauncher(config);

        adminClient = new SessionDbAdminClient(launcher.getServiceLocatorForAdmin(), launcher.getAdminToken());

        // we can use scheduler's service-locator client to get the internal addresses
        sessionDbClientForFileBroker = new SessionDbAdminClient(launcher.getServiceLocatorForScheduler(),
                launcher.getFileBrokerToken());

        user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
        user2Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);

        fileBrokerTarget1 = launcher.getUser1Target(Role.FILE_BROKER);
    }

    @AfterAll
    public static void tearDown() throws Exception {
        launcher.stop();
    }

    @Test
    public void getQuotasForUser() throws IOException, RestException {

        Session session1 = RestUtils.getRandomSession();
        user1Client.createSession(session1);

        // admin can get quotas for user1
        String user1IdString = launcher.getUser1Credentials().getUsername();

        String json = adminClient.getQuotasForUser(user1IdString);
        List<HashMap<String, Object>> resultList = getResultsList(json);

        assertTrue(resultList.get(0).get("userId").equals(user1IdString));
        assertTrue(((Integer) resultList.get(0).get("readWriteSessions")) > 0);
    }

    @Test
    public void getQuotaForMultipleUsers() throws IOException, RestException {

        // create sessions for users 1 and 2
        Session session1 = RestUtils.getRandomSession();
        Session session2 = RestUtils.getRandomSession();
        Session session3 = RestUtils.getRandomSession();
        Session session4 = RestUtils.getRandomSession();
        user1Client.createSession(session1);
        user1Client.createSession(session2);
        user2Client.createSession(session3);
        user2Client.createSession(session4);

        // admin can get all sessions for users 1 and 2
        String user1IdString = launcher.getUser1Credentials().getUsername();
        String user2IdString = launcher.getUser2Credentials().getUsername();

        String json = adminClient.getQuotasForUser(user1IdString, user2IdString);
        List<HashMap<String, Object>> resultList = getResultsList(json);

        HashMap<String, Object> user1Quotas = getResultQuotasForUser(user1IdString, resultList);
        HashMap<String, Object> user2Quotas = getResultQuotasForUser(user2IdString, resultList);

        assertTrue((Integer) user1Quotas.get("readWriteSessions") >= 2);
        assertTrue((Integer) user2Quotas.get("readWriteSessions") >= 2);
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
        List<HashMap<String, Object>> resultList = getResultsList(json);

        List<HashMap<String, Object>> user1Sessions = getResultSessionsForUser(user1IdString, resultList);
        assertTrue(containsSession(sessionId1.toString(), user1Sessions));
    }

    @Test
    public void getSessionsForMultipleUsers() throws IOException, RestException {

        // create sessions for users 1 and 2
        Session session1 = RestUtils.getRandomSession();
        Session session2 = RestUtils.getRandomSession();
        Session session3 = RestUtils.getRandomSession();
        Session session4 = RestUtils.getRandomSession();
        UUID sessionId1 = user1Client.createSession(session1);
        UUID sessionId2 = user1Client.createSession(session2);
        UUID sessionId3 = user2Client.createSession(session3);
        UUID sessionId4 = user2Client.createSession(session4);

        // admin can get all sessions for users 1 and 2
        String user1IdString = launcher.getUser1Credentials().getUsername();
        String user2IdString = launcher.getUser2Credentials().getUsername();

        String json = adminClient.getSessionsForUser(user1IdString, user2IdString);
        List<HashMap<String, Object>> resultList = getResultsList(json);

        List<HashMap<String, Object>> user1Sessions = getResultSessionsForUser(user1IdString, resultList);
        List<HashMap<String, Object>> user2Sessions = getResultSessionsForUser(user2IdString, resultList);

        assertTrue(containsSession(sessionId1.toString(), user1Sessions));
        assertTrue(containsSession(sessionId2.toString(), user1Sessions));
        assertTrue(containsSession(sessionId3.toString(), user2Sessions));
        assertTrue(containsSession(sessionId4.toString(), user2Sessions));

        assertFalse(containsSession(sessionId3.toString(), user1Sessions));
        assertFalse(containsSession(sessionId4.toString(), user1Sessions));
        assertFalse(containsSession(sessionId1.toString(), user2Sessions));
        assertFalse(containsSession(sessionId2.toString(), user2Sessions));
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
        String json = adminClient.getSessionsForUser(user1IdString);
        List<HashMap<String, Object>> resultList = getResultsList(json);

        List<HashMap<String, Object>> user1Sessions = getResultSessionsForUser(user1IdString, resultList);
        assertTrue(user1Sessions.size() == 0);
    }

    @Test
    public void deleteSessionsForMultipleUsers() throws IOException, RestException {

        // create sessions for users 1 and 2
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
        String user1sessionsJson = adminClient.getSessionsForUser(user1IdString);
        List<HashMap<String, Object>> user1ResultList = getResultsList(user1sessionsJson);

        List<HashMap<String, Object>> user1Sessions = getResultSessionsForUser(user1IdString, user1ResultList);
        assertTrue(user1Sessions.size() == 0);

        // no more sessions at all for user2
        String user2sessionsJson = adminClient.getSessionsForUser(user2IdString);
        List<HashMap<String, Object>> user2ResultList = getResultsList(user2sessionsJson);

        List<HashMap<String, Object>> user2Sessions = getResultSessionsForUser(user2IdString, user2ResultList);
        assertTrue(user2Sessions.size() == 0);
    }

    private List<HashMap<String, Object>> getResultsList(String json)
            throws JsonMappingException, JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        List<HashMap<String, Object>> resultMaps = mapper.readValue(json,
                new TypeReference<List<HashMap<String, Object>>>() {
                });
        return resultMaps;
    }

    private HashMap<String, Object> getResultQuotasForUser(String userId, List<HashMap<String, Object>> results) {

        List<HashMap<String, Object>> filteredResultsList = results.stream()
                .filter(singleUserResultMap -> singleUserResultMap.get("userId").equals(userId))
                .collect(Collectors.toList());

        // the results should only contain each userId once
        assertTrue(filteredResultsList.size() == 1);
        return filteredResultsList.get(0);
    }

    /**
     * Returns the list of sessions for a single user
     * 
     * @param userId
     * @param results
     * @return
     */
    private List<HashMap<String, Object>> getResultSessionsForUser(String userId,
            List<HashMap<String, Object>> results) {

        @SuppressWarnings("unchecked")
        List<List<HashMap<String, Object>>> filteredResultsList = results.stream()
                .filter(singleUserResultMap -> singleUserResultMap.get("userId").equals(userId))
                .map(singleUserResultMap -> (List<HashMap<String, Object>>) singleUserResultMap.get("sessions"))
                .collect(Collectors.toList());

        // the results should only contain each userId once
        assertTrue(filteredResultsList.size() == 1);
        return filteredResultsList.get(0);
    }

    private boolean containsSession(String sessionId, List<HashMap<String, Object>> sessionList) {
        return sessionList.stream().anyMatch(session -> session.get("sessionId").equals(sessionId));
    }

    @Test
    public void testFiles() throws IOException, RestException {

        // create sessions for users 1 and 2
        Session session = RestUtils.getRandomSession();
        UUID sessionId = user1Client.createSession(session);

        Dataset dataset = RestUtils.getRandomDataset();
        UUID datasetId = user1Client.createDataset(sessionId, dataset);

        Dataset dataset2 = RestUtils.getRandomDataset();
        UUID datasetId2 = user1Client.createDataset(sessionId, dataset2);

        String contents = "abc";
        FileResourceTest.uploadInputStream(fileBrokerTarget1, sessionId, datasetId,
                new ByteArrayInputStream(contents.getBytes()), contents.length());

        // file-broker should have added a File object to the Dataset
        dataset = user1Client.getDataset(sessionId, datasetId);

        File file = dataset.getFile();
        UUID fileId = file.getFileId();
        String storageId = file.getStorage();

        // file-broker can get files

        Set<UUID> storageFiles = sessionDbClientForFileBroker.getFiles(storageId, FileState.COMPLETE).stream()
                .map(f -> f.getFileId())
                .collect(Collectors.toSet());

        assertTrue(storageFiles.contains(fileId));

        // check that file-broker can modify Files
        file.setStorage("fake-storage-id");
        sessionDbClientForFileBroker.updateFile(file);

        // put back original value
        file.setStorage(storageId);
        sessionDbClientForFileBroker.updateFile(file);

        sessionDbClientForFileBroker.deleteFile(file.getFileId());

        // dataset shouldn't exist anymore
        try {
            user1Client.getDataset(sessionId, datasetId);
        } catch (RestException e) {
            assertEquals(404, e.getResponse().getStatus());
        }

        // other datasets should remain
        assertTrue(user1Client.getDataset(sessionId, datasetId2) != null);

        user1Client.deleteSession(sessionId);
    }

    @Test
    public void testFileErrors() throws IOException, RestException {

        // create sessions for users 1 and 2
        Session session = RestUtils.getRandomSession();
        UUID sessionId = user1Client.createSession(session);

        Dataset dataset = RestUtils.getRandomDataset();
        UUID datasetId = user1Client.createDataset(sessionId, dataset);

        String contents = "abc";
        FileResourceTest.uploadInputStream(fileBrokerTarget1, sessionId, datasetId,
                new ByteArrayInputStream(contents.getBytes()), contents.length());

        // file-broker should have added a File object to the Dataset
        dataset = user1Client.getDataset(sessionId, datasetId);

        File file = dataset.getFile();
        String storageId = file.getStorage();

        SessionDbAdminClient sessionDbAdminClientForUser = new SessionDbAdminClient(
                launcher.getServiceLocatorForScheduler(),
                launcher.getUser1Token());

        // users never need direct access to File
        try {
            sessionDbAdminClientForUser.getFiles(storageId, null);
        } catch (RestException e) {
            assertEquals(403, e.getResponse().getStatus());
        }

        // users never need direct access to File
        try {
            file.setStorage("fake-storage-id");
            sessionDbAdminClientForUser.updateFile(file);

        } catch (RestException e) {
            assertEquals(403, e.getResponse().getStatus());
        }

        // users never need direct access to File
        try {
            sessionDbAdminClientForUser.deleteFile(file.getFileId());

        } catch (RestException e) {
            assertEquals(403, e.getResponse().getStatus());
        }

        user1Client.deleteSession(sessionId);
    }
}