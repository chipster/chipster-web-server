package fi.csc.chipster.filebroker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.client.ClientProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbAdminClient;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class FileResourceTest {

	private Logger logger = LogManager.getLogger();

	private static final String TEST_FILE = "build.gradle";
	private static TestServerLauncher launcher;
	private static WebTarget fileBrokerTarget1;
	private static WebTarget fileBrokerTarget2;
	private static SessionDbClient sessionDbClient1;
	private static SessionDbClient sessionDbClient2;
	private static UUID sessionId1;
	private static UUID sessionId2;
	private static SessionDbAdminClient sessionDbForFileBrokerClient;

	@BeforeAll
	public static void setUp() throws Exception {
		Config config = new Config();
		launcher = new TestServerLauncher(config);

		sessionDbClient1 = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		sessionDbClient2 = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token(), Role.CLIENT);

		fileBrokerTarget1 = launcher.getUser1Target(Role.FILE_BROKER);
		fileBrokerTarget2 = launcher.getUser2Target(Role.FILE_BROKER);

		// file-broker rights allow breaking things to test errors
		sessionDbForFileBrokerClient = new SessionDbAdminClient(launcher.getServiceLocatorForAdmin(),
				launcher.getFileBrokerToken());

		sessionId1 = sessionDbClient1.createSession(RestUtils.getRandomSession());
		sessionId2 = sessionDbClient2.createSession(RestUtils.getRandomSession());
	}

	@AfterAll
	public static void tearDown() throws Exception {
		launcher.stop();
	}

	@Test
	public void putAndChange() throws FileNotFoundException, RestException {

		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());

		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());

		// not possible to upload a new file, if the dataset has one already
		assertEquals(409, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());
	}

	@Test
	public void putInChunks() throws RestException, IOException {

		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());

		long chunk1Length = 10 * 1024 * 1024;
		long chunk2Length = 1 * 1024 * 1024;

		InputStream chunk1Stream = new DummyInputStream(chunk1Length);
		InputStream chunk1StreamRef = new DummyInputStream(chunk1Length);
		InputStream chunk2Stream = new DummyInputStream(chunk2Length);
		@SuppressWarnings("resource")
		InputStream chunk2StreamRef = new DummyInputStream(chunk2Length);

		WebTarget target = getChunkedTarget(fileBrokerTarget1, sessionId1, datasetId, chunk1Length + chunk2Length)
				.queryParam("flowChunkNumber", "1")
				.queryParam("flowChunkSize", "" + chunk1Length)
				.queryParam("flowCurrentChunkSize", "" + chunk1Length)
				.queryParam("flowIdentifier", "JUnit-test-flow")
				.queryParam("flowFilename", "JUnit-test-flow")
				.queryParam("flowRelativePath", "JUnit-test-flow")
				.queryParam("flowTotalChunks", "2");
		assertEquals(204, putInputStream(target, chunk1Stream).getStatus());

		target = getChunkedTarget(fileBrokerTarget1, sessionId1, datasetId, chunk1Length + chunk2Length)
				.queryParam("flowChunkNumber", "2")
				.queryParam("flowChunkSize", "" + chunk1Length) // default chunk size, but
				.queryParam("flowCurrentChunkSize", "" + chunk2Length) // the last can be different
				.queryParam("flowIdentifier", "JUnit-test-flow")
				.queryParam("flowFilename", "JUnit-test-flow")
				.queryParam("flowRelativePath", "JUnit-test-flow")
				.queryParam("flowTotalChunks", "2");
		assertEquals(204, putInputStream(target, chunk2Stream).getStatus());

		InputStream remoteStream = fileBrokerTarget1.path(getDatasetPath(sessionId1, datasetId)).request()
				.get(InputStream.class);
		InputStream referenceStream = new SequenceInputStream(chunk1StreamRef, chunk2StreamRef);

		assertEquals(true, IOUtils.contentEquals(remoteStream, referenceStream));

		// check that file-broker has set the correct size for the dataset
		Dataset dataset = sessionDbClient1.getDataset(sessionId1, datasetId);
		assertEquals(chunk1Length + chunk2Length, dataset.getFile().getSize());
	}

	@Test
	public void putWrongUser() throws FileNotFoundException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(403, uploadFile(fileBrokerTarget2, sessionId1, datasetId).getStatus());
	}

	@Test
	public void putAuthFail() throws FileNotFoundException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(401, uploadFile(launcher.getAuthFailTarget(Role.FILE_BROKER), sessionId1, datasetId).getStatus());
	}

	@Test
	public void putTokenFail() throws FileNotFoundException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(403,
				uploadFile(launcher.getWrongTokenTarget(Role.FILE_BROKER), sessionId1, datasetId).getStatus());
	}

	@Test
	public void putUnparseableToken() throws FileNotFoundException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(401,
				uploadFile(launcher.getUnparseableTokenTarget(Role.FILE_BROKER), sessionId1, datasetId).getStatus());
	}

	@Test
	public void putWrongSession() throws FileNotFoundException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(403, uploadFile(fileBrokerTarget1, sessionId2, datasetId).getStatus());
	}

	@Test
	public void putAndGetFile() throws RestException, IOException {

		// test different file lengths
		// lengths from 0 to 20 should be enough to check encryption padding which is 16
		// bytes (but S3 storage is not enabled by default)
		for (long length = 0; length < 20; length++) {
			putAndGetFile(length);
		}
	}

	public void putAndGetFile(long length) throws RestException, IOException {

		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(204, uploadInputStream(fileBrokerTarget1, sessionId1, datasetId,
				new DummyInputStream(length), length).getStatus());

		InputStream remoteStream = fileBrokerTarget1.path(getDatasetPath(sessionId1, datasetId)).request()
				.get(InputStream.class);

		assertEquals(true, IOUtils.contentEquals(remoteStream, new DummyInputStream(length)));
	}

	// @Test
	public void putLargeFile() throws FileNotFoundException, RestException {
		long length = 6 * 1024 * 1024 * 1024;
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(200, uploadInputStream(fileBrokerTarget1, sessionId1, datasetId,
				new DummyInputStream(length), length).getStatus());
	}

	private Response uploadLargeFile(WebTarget target, UUID sessionId, UUID datasetId, long length)
			throws FileNotFoundException {

		return uploadInputStream(target, sessionId, datasetId, new DummyInputStream(length), length);
	}

	private Response uploadFile(WebTarget target, UUID sessionId, UUID datasetId) throws FileNotFoundException {
		File file = new File(TEST_FILE);
		InputStream fileInStream = new FileInputStream(file);

		return uploadInputStream(target, sessionId, datasetId, fileInStream, file.length());
	}

	public static Response uploadInputStream(WebTarget target, UUID sessionId, UUID datasetId,
			InputStream inputStream, long length) {
		WebTarget chunkedTarget = getChunkedTarget(target, sessionId, datasetId, length);
		return putInputStream(chunkedTarget, inputStream);
	}

	private static Response putInputStream(WebTarget chunkedTarget, InputStream inputStream) {
		return chunkedTarget.request().put(Entity.entity(inputStream, MediaType.APPLICATION_OCTET_STREAM),
				Response.class);
	}

	private static WebTarget getChunkedTarget(WebTarget target, UUID sessionId, UUID datasetId, long length) {
		// Use chunked encoding to disable buffering. HttpUrlConnector in
		// Jersey buffers the whole file before sending it by default, which
		// won't work with big files.
		target = target.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
		target = target.queryParam("flowTotalSize", Long.toString(length));
		return target.path(getDatasetPath(sessionId, datasetId));
	}

	private static String getDatasetPath(UUID sessionId, UUID datasetId) {
		return "sessions/" + sessionId.toString() + "/datasets/" + datasetId.toString();
	}

	public static class DummyInputStream extends InputStream {

		long bytes = 0;
		private long size;

		public DummyInputStream(long size) {
			this.size = size;
		}

		@Override
		public int read() {
			if (bytes < size) {
				bytes++;
				// don't convert to byte, because it would be signed and this could return -1
				// making the stream shorter
				return (int) (bytes % 256);
			} else {
				return -1;
			}
		}
	}

	@Test
	public void get() throws IOException, RestException {

		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());

		InputStream remoteStream = fileBrokerTarget1.path(getDatasetPath(sessionId1, datasetId)).request()
				.get(InputStream.class);
		InputStream fileStream = new FileInputStream(new File(TEST_FILE));

		assertEquals(true, IOUtils.contentEquals(remoteStream, fileStream));

		// check that file-broker has set the correct size for the dataset
		Dataset dataset = sessionDbClient1.getDataset(sessionId1, datasetId);
		assertEquals(new File(TEST_FILE).length(), dataset.getFile().getSize());
	}

	@Test
	public void getError() throws IOException, RestException, CloneNotSupportedException {

		long length = 10 * 1024 * 1024;

		// long length = 10;

		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(204,
				uploadLargeFile(fileBrokerTarget1, sessionId1, datasetId, length).getStatus());

		// now we can get it
		InputStream remoteStream = fileBrokerTarget1.path(getDatasetPath(sessionId1,
				datasetId)).request()
				.get(InputStream.class);
		assertEquals(true, IOUtils.contentEquals(remoteStream, new DummyInputStream(length)));

		// change size on server
		Dataset dataset = sessionDbClient1.getDataset(sessionId1, datasetId);
		fi.csc.chipster.sessiondb.model.File file = dataset.getFile();
		fi.csc.chipster.sessiondb.model.File brokenFile = (fi.csc.chipster.sessiondb.model.File) file.clone();
		brokenFile.setChecksum("aaaaaaaa");
		sessionDbForFileBrokerClient.updateFile(brokenFile);

		try {

			WebTarget target = fileBrokerTarget1.path(getDatasetPath(sessionId1, datasetId));
			Response response = target.request().get(Response.class);

			for (String key : response.getHeaders().keySet()) {
				System.out.println(key + ": " + response.getHeaders().get(key));
			}

			if (!RestUtils.isSuccessful(response.getStatus())) {
				throw new RestException("get zip session stream error", response, target.getUri());
			}

			remoteStream = response.readEntity(InputStream.class);

			// reading the zip stream should throw IOException: Premature EOF
			assertEquals(true, IOUtils.contentEquals(remoteStream, new DummyInputStream(length)));

			fail("expected exception was not thrown");

		} catch (Exception e) {
			logger.error("expected error", e);
			// expected error, test was successful
		}

	}

	@Test
	public void getSharedFile() throws IOException, RestException {

		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());
		// dataset should now have a file id
		fi.csc.chipster.sessiondb.model.File file = sessionDbClient1.getDataset(sessionId1, datasetId).getFile();
		assertEquals(true, file.getFileId() != null);

		// create a new dataset of the same file
		Dataset dataset = RestUtils.getRandomDataset();
		dataset.setFile(file);
		UUID datasetId2 = sessionDbClient1.createDataset(sessionId1, dataset);

		// we should be able to read both datasets, although we haven't uploaded the
		// second dataset
		checkFile(sessionId1, datasetId);
		checkFile(sessionId1, datasetId2);

		// remove the original dataset
		sessionDbClient1.deleteDataset(sessionId1, datasetId);

		// the first dataset must not work anymore
		try {
			checkFile(sessionId1, datasetId);
			assertEquals(true, false);
		} catch (NotFoundException e) {
		}

		// and the second must be still readable
		checkFile(sessionId1, datasetId2);
	}

	@Test
	public void delete() throws IOException, InterruptedException, RestException {

		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());

		// dataset should now have a file id
		fi.csc.chipster.sessiondb.model.File file = sessionDbClient1.getDataset(sessionId1, datasetId).getFile();
		assertEquals(true, file.getFileId() != null);

		// check that we can find the file
		String partition = file.getFileId().toString().substring(0, 2);
		File storageFile = new File(
				"storage" + File.separator + partition + File.separator + file.getFileId().toString());

		if (!storageFile.exists()) {
			throw new RuntimeException(
					"test doesn't support s3 storage. File not found from file-storage. Is s3 storage enabled?");
		}

		// remove the dataset
		sessionDbClient1.deleteDataset(sessionId1, datasetId);

		// wait a while and check that the file is removed also
		Thread.sleep(100);
		assertEquals(false, storageFile.exists());
	}

	private void checkFile(UUID sessionId, UUID datasetId) throws IOException {
		InputStream remoteStream = fileBrokerTarget1.path(getDatasetPath(sessionId, datasetId)).request()
				.get(InputStream.class);
		InputStream fileStream = new FileInputStream(new File(TEST_FILE));
		assertEquals(true, IOUtils.contentEquals(remoteStream, fileStream));
	}

	@Test
	public void getAuthFail() throws IOException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());
		assertEquals(401, launcher.getAuthFailTarget(Role.FILE_BROKER).path(getDatasetPath(sessionId1, datasetId))
				.request().get(Response.class).getStatus());
	}

	@Test
	public void getNoAuth() throws IOException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());
		assertEquals(401, launcher.getNoAuthTarget(Role.FILE_BROKER).path(getDatasetPath(sessionId1, datasetId))
				.request().get(Response.class).getStatus());
	}

	@Test
	public void getTokenFail() throws IOException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());
		assertEquals(403, launcher.getWrongTokenTarget(Role.FILE_BROKER).path(getDatasetPath(sessionId1, datasetId))
				.request().get(Response.class).getStatus());
	}

	@Test
	public void getUnparseableToken() throws IOException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());
		assertEquals(401, launcher.getUnparseableTokenTarget(Role.FILE_BROKER)
				.path(getDatasetPath(sessionId1, datasetId)).request().get(Response.class).getStatus());
	}

	@Test
	public void getWrongSession() throws IOException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());
		assertEquals(403, fileBrokerTarget1.path(getDatasetPath(sessionId2, datasetId)).request().get(Response.class)
				.getStatus());
	}
}
