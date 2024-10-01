package fi.csc.chipster.sessionworker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.filestorage.FileServlet;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.s3storage.client.S3StorageClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Session;

public class ZipSessionServletTest {

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private static final String TEST_FILE = "build.gradle";
	private static TestServerLauncher launcher;

	private static SessionDbClient sessionDbClient1;
	private static SessionDbClient sessionDbClient2;

	private static RestFileBrokerClient fileBrokerClient1;
	private static RestFileBrokerClient fileBrokerClient2;

	private static SessionWorkerClient sessionWorkerClient1;
	private static SessionWorkerClient sessionWorkerClient2;

	@BeforeAll
	public static void setUp() throws Exception {
		Config config = new Config();
		launcher = new TestServerLauncher(config);

		sessionDbClient1 = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		sessionDbClient2 = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);

		fileBrokerClient1 = new RestFileBrokerClient(launcher.getServiceLocator(), launcher.getUser1Token(),
				Role.CLIENT);
		fileBrokerClient2 = new RestFileBrokerClient(launcher.getServiceLocator(), launcher.getUser2Token(),
				Role.CLIENT);

		sessionWorkerClient1 = new SessionWorkerClient(launcher.getUser1Target(Role.SESSION_WORKER), sessionDbClient1,
				fileBrokerClient1);
		sessionWorkerClient2 = new SessionWorkerClient(launcher.getUser2Target(Role.SESSION_WORKER), sessionDbClient2,
				fileBrokerClient2);
	}

	@AfterAll
	public static void tearDown() throws Exception {
		launcher.stop();
	}

	@Test
	public void downloadAndUploadZip() throws RestException, IOException {

		UUID sessionId1 = sessionDbClient1.createSession(RestUtils.getRandomSession());

		Dataset originalDataset = RestUtils.getRandomDataset();
		originalDataset.setCreated(Instant.now());

		// upload some file to the session
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, originalDataset);
		fileBrokerClient1.upload(sessionId1, datasetId, new File(TEST_FILE));

		// download the session zip
		UUID zipDatasetId = sessionWorkerClient1.packageSessionToZip(sessionId1);

		InputStream zipByteStream = fileBrokerClient1.download(sessionId1, zipDatasetId);

		byte[] zipBytes = IOUtils.toByteArray(zipByteStream);

		// upload the session zip
		UUID sessionId2 = sessionWorkerClient2.uploadZipSession(new ByteArrayInputStream(zipBytes),
				zipBytes.length);

		// find the correct dataset from the extracted session
		HashMap<UUID, Dataset> datasets = sessionDbClient2.getDatasets(sessionId2);

		Dataset resultDataset = datasets.values().stream()
				.filter(d -> originalDataset.getName().equals(d.getName()))
				.findAny().get();

		// check that the result dataset and the original file are the same
		InputStream remoteStream = fileBrokerClient2.download(sessionId2, resultDataset.getDatasetId());
		InputStream fileStream = new FileInputStream(new File(TEST_FILE));
		assertEquals(true, IOUtils.contentEquals(remoteStream, fileStream));

		// check that a timestamp was preserved
		assertEquals(originalDataset.getCreated(), resultDataset.getCreated());

		sessionDbClient1.deleteSession(sessionId1);
		sessionDbClient2.deleteSession(sessionId2);
	}

	/**
	 * Check that client is informed when an error happens during the compression
	 * and download of the zip package
	 * 
	 * @throws RestException
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws NoSuchAlgorithmException
	 * @throws KeyManagementException
	 */
	@Test
	public void compressionError()
			throws RestException, IOException, InterruptedException, NoSuchAlgorithmException, KeyManagementException {

		Session session = RestUtils.getRandomSession();

		UUID sessionId = sessionDbClient1.createSession(session);

		// upload some file to the session
		Dataset dataset = RestUtils.getRandomDataset();
		UUID datasetId = sessionDbClient1.createDataset(sessionId, dataset);
		fileBrokerClient1.upload(sessionId, datasetId, new File(TEST_FILE));

		// break the session by deleting the file from the file-storage
		fi.csc.chipster.sessiondb.model.File file = sessionDbClient1.getDataset(sessionId, datasetId).getFile();

		try {
			Files.delete(FileServlet.getStoragePath(Paths.get("storage"), file.getFileId()));
		} catch (NoSuchFileException e) {
			// apparently this installation is configured to use s3-storage
			S3StorageClient s3StorageClient = new S3StorageClient(new Config(), null);
			s3StorageClient.delete(file);
		}

		// download the session zip
		try {

			// the server should return some errors in the json and this should throw an
			// exception
			sessionWorkerClient1.packageSessionToZip(sessionId);

		} catch (RestException e) {
			// this is expected
			e.printStackTrace();
		}

		sessionDbClient1.deleteSession(sessionId);
	}

	@Test
	public void getWrongUser() throws RestException {

		UUID sessionId = sessionDbClient1.createSession(RestUtils.getRandomSession());

		try {
			// user2 shouldn't be able to create a zip for user1
			sessionWorkerClient2.packageSessionToZip(sessionId);
			Assertions.fail();
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}

		sessionDbClient1.deleteSession(sessionId);
	}

	@Test
	public void postWrongUser() throws RestException, IOException {

		UUID sessionId1 = sessionDbClient1.createSession(RestUtils.getRandomSession());
		UUID sessionId2 = sessionDbClient1.createSession(RestUtils.getRandomSession());

		// get a zip stream and upload the zip to a session owned by user1
		UUID zipDatasetId = setupExtactionTest(sessionId1, sessionId2);

		try {
			// user2 shouldn't be able to extract a session owner by user1
			sessionWorkerClient2.extractZipSession(sessionId2, zipDatasetId);
			Assertions.fail();
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}

		sessionDbClient1.deleteSession(sessionId1);
		sessionDbClient1.deleteSession(sessionId2);
	}

	@Test
	public void getAuthFail() throws RestException {
		SessionWorkerClient client = new SessionWorkerClient(launcher.getAuthFailTarget(Role.SESSION_WORKER),
				sessionDbClient1, fileBrokerClient1);
		this.testGetAuth(client, 401);
	}

	@Test
	public void postAuthFail() throws RestException, IOException {

		SessionWorkerClient client = new SessionWorkerClient(launcher.getAuthFailTarget(Role.SESSION_WORKER),
				sessionDbClient1, fileBrokerClient1);
		this.testPostAuth(client, 401);
	}

	@Test
	public void getTokenFail() throws RestException {
		SessionWorkerClient client = new SessionWorkerClient(launcher.getWrongTokenTarget(Role.SESSION_WORKER),
				sessionDbClient1, fileBrokerClient1);
		this.testGetAuth(client, 403);
	}

	@Test
	public void postTokenFail() throws RestException, IOException {
		SessionWorkerClient client = new SessionWorkerClient(launcher.getWrongTokenTarget(Role.SESSION_WORKER),
				sessionDbClient1, fileBrokerClient1);
		this.testPostAuth(client, 401);
	}

	@Test
	public void getUnparseableToken() throws RestException {
		SessionWorkerClient client = new SessionWorkerClient(launcher.getUnparseableTokenTarget(Role.SESSION_WORKER),
				sessionDbClient1, fileBrokerClient1);
		this.testGetAuth(client, 401);
	}

	@Test
	public void postUnparseableToken() throws RestException, IOException {
		SessionWorkerClient client = new SessionWorkerClient(launcher.getUnparseableTokenTarget(Role.SESSION_WORKER),
				sessionDbClient1, fileBrokerClient1);
		this.testPostAuth(client, 401);
	}

	@Test
	public void getNoAuth() throws RestException {
		SessionWorkerClient client = new SessionWorkerClient(launcher.getNoAuthTarget(Role.SESSION_WORKER),
				sessionDbClient1, fileBrokerClient1);
		this.testGetAuth(client, 401);
	}

	@Test
	public void postNoAuth() throws RestException, IOException {
		SessionWorkerClient client = new SessionWorkerClient(launcher.getNoAuthTarget(Role.SESSION_WORKER),
				sessionDbClient1, fileBrokerClient1);
		this.testPostAuth(client, 401);
	}

	/**
	 * Test that setupExctractionTest() works, because other tests rely on it
	 * 
	 * @throws RestException
	 * @throws IOException
	 */
	@Test
	public void setupTest() throws RestException, IOException {

		Session session = RestUtils.getRandomSession();

		UUID sessionId1 = sessionDbClient1.createSession(session);
		UUID sessionId2 = sessionDbClient1.createSession(RestUtils.getRandomSession());

		// get a zip stream and upload the zip to a session owned by user1
		UUID zipDatasetId = setupExtactionTest(sessionId1, sessionId2);

		sessionWorkerClient1.extractZipSession(sessionId2, zipDatasetId);

		assertEquals(session.getName(), sessionDbClient1.getSession(sessionId2).getName());

		sessionDbClient1.deleteSession(sessionId1);
		sessionDbClient1.deleteSession(sessionId2);
	}

	public void testGetAuth(SessionWorkerClient client, int expectedStatusCode) throws RestException {

		UUID sessionId = sessionDbClient1.createSession(RestUtils.getRandomSession());

		try {
			client.packageSessionToZip(sessionId);
			Assertions.fail();
		} catch (RestException e) {
			assertEquals(expectedStatusCode, e.getResponse().getStatus());
		}
		sessionDbClient1.deleteSession(sessionId);
	}

	public void testPostAuth(SessionWorkerClient sessionWorkerClient, int expectedStatusCode)
			throws RestException, IOException {

		UUID sessionId1 = sessionDbClient1.createSession(RestUtils.getRandomSession());
		UUID sessionId2 = sessionDbClient1.createSession(RestUtils.getRandomSession());

		// get a zip stream and upload the zip to a session owned by user1
		UUID zipDatasetId = setupExtactionTest(sessionId1, sessionId2);

		try {
			SessionWorkerClient client = new SessionWorkerClient(launcher.getAuthFailTarget(Role.SESSION_WORKER),
					sessionDbClient1, fileBrokerClient1);
			client.extractZipSession(sessionId1, zipDatasetId);
			Assertions.fail();
		} catch (RestException e) {
			assertEquals(expectedStatusCode, e.getResponse().getStatus());
		}

		sessionDbClient1.deleteSession(sessionId1);
		sessionDbClient1.deleteSession(sessionId2);
	}

	private UUID setupExtactionTest(UUID sourceSession, UUID targetSession) throws RestException, IOException {

		UUID zipDatasetIdForDownload = sessionWorkerClient1.packageSessionToZip(sourceSession);

		InputStream zipStream = fileBrokerClient1.download(sourceSession, zipDatasetIdForDownload);

		// copy to array to get the length
		byte[] bytes = IOUtils.toByteArray(zipStream);

		Dataset zipDataset = new Dataset();
		UUID zipDatasetId = sessionDbClient1.createDataset(targetSession, zipDataset);
		fileBrokerClient1.upload(targetSession, zipDatasetId, new ByteArrayInputStream(bytes), (long) bytes.length);

		return zipDatasetId;
	}
}
