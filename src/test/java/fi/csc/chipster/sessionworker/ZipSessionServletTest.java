package fi.csc.chipster.sessionworker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
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
		
        fileBrokerClient1 = new RestFileBrokerClient(launcher.getUser1Target(Role.FILE_BROKER).getUri().toString(), launcher.getUser1Token());
        fileBrokerClient2 = new RestFileBrokerClient(launcher.getUser2Target(Role.FILE_BROKER).getUri().toString(), launcher.getUser2Token());
        
        sessionWorkerClient1 = new SessionWorkerClient(launcher.getUser1Target(Role.SESSION_WORKER), sessionDbClient1, fileBrokerClient1);
		sessionWorkerClient2 = new SessionWorkerClient(launcher.getUser2Target(Role.SESSION_WORKER), sessionDbClient2, fileBrokerClient2);
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
		InputStream zipBytes = sessionWorkerClient1.getZipSessionStream(sessionId1);

		// upload the session zip
		UUID sessionId2 = sessionWorkerClient2.uploadZipSession(zipBytes);
				
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
	 * Check that the HTTP transfer end is abortive (instead of orderly) 
	 * when an error happens during the compression and download of the zip package.
	 * See {@link ZipSessionServlet}.
	 * 
	 * See https://docs.oracle.com/javase/8/docs/technotes/guides/net/articles/connection_release.html
	 * 
	 * A valid chunked transfer encoding ends with a zero sized chunk. If the server has already sent 
	 * the status code 200, it can still signal about the error by finishing the TCP connection 
	 * without that empty chunk.  
	 * 
	 * @throws RestException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	@Test
    public void compressionError() throws RestException, IOException, InterruptedException {
		
		Session session = RestUtils.getRandomSession();
		
		/* If the response is short, Jetty simply buffers it, notices the exception and
		 * responds with status code 500. The situation is more difficult, when 
		 * the status code has been already sent. 
		 * 
		 * Easiest way to cause the error
		 * is to remove the file from the file-storage, but we need to somehow
		 * make sure that that response before that error has exceeded the buffering 
		 * limit. Adding
		 * other large files is unreliable, because we don't know the order of the 
		 * files in the zip packge. However, we know that that metadata is written
		 * to the beginning of the zip package. Add a huge notes to the metadata, 
		 * to make sure that the Jetty has flushed the start of the response, before
		 * it encounters the missing file. 
		 */
		StringBuffer notes = new StringBuffer();
		
		// write until then notes is 10 MB
		while (notes.length() < 10 * 1024 * 1024) {
			notes.append(UUID.randomUUID());
		}
				
		session.setNotes(notes.toString());		
		
        UUID sessionId = sessionDbClient1.createSession(session);
				
		// upload some file to the session		
        Dataset dataset = RestUtils.getRandomDataset();
		UUID datasetId = sessionDbClient1.createDataset(sessionId, dataset);
		fileBrokerClient1.upload(sessionId, datasetId, new File(TEST_FILE));
		
		// break the session by deleting the file from the file-storage
		UUID fileId = sessionDbClient1.getDataset(sessionId, datasetId).getFile().getFileId();		
		Files.delete(FileServlet.getStoragePath(Paths.get("storage"), fileId));
				
		// download the session zip
		try {		
			
			InputStream body = sessionWorkerClient1.getZipSessionStream(sessionId);
			
			// reading the zip stream should throw IOException: Premature EOF
			IOUtils.copy(body, OutputStream.nullOutputStream());		
			
			assertEquals(true, false);
			
		} catch (RestException e) {
			throw new RuntimeException("test is broken, Jetty didn't start streaming yet", e);
			
		} catch (IOException e) {
			// this is expected
			e.printStackTrace();
		}			

		sessionDbClient1.deleteSession(sessionId);
    }
	
	@Test
    public void getWrongUser() throws RestException {
		
		UUID sessionId = sessionDbClient1.createSession(RestUtils.getRandomSession());
		
		try {
			// user2 shouldn't be able to get the zip of user1
			sessionWorkerClient2.getZipSessionStream(sessionId);
			Assertions.fail();
		} catch (RestException e) {
			assertEquals(403, e.getResponse().getStatus());
		}
		
		sessionDbClient1.deleteSession(sessionId);
    }
	
	@Test
    public void postWrongUser() throws RestException {
				
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
		SessionWorkerClient client = new SessionWorkerClient(launcher.getAuthFailTarget(Role.SESSION_WORKER), sessionDbClient1, fileBrokerClient1);
		this.testGetAuth(client, 401);
	}
	
	@Test
    public void postAuthFail() throws RestException {
		
		SessionWorkerClient client = new SessionWorkerClient(launcher.getAuthFailTarget(Role.SESSION_WORKER), sessionDbClient1, fileBrokerClient1);		
		this.testPostAuth(client, 401);
	}

	@Test
    public void getTokenFail() throws RestException {
		SessionWorkerClient client = new SessionWorkerClient(launcher.getWrongTokenTarget(Role.SESSION_WORKER), sessionDbClient1, fileBrokerClient1);
		this.testGetAuth(client, 403);
	}
	
	@Test
    public void postTokenFail() throws RestException {	
		SessionWorkerClient client = new SessionWorkerClient(launcher.getWrongTokenTarget(Role.SESSION_WORKER), sessionDbClient1, fileBrokerClient1);		
		this.testPostAuth(client, 401);
	}
	
	@Test
    public void getUnparseableToken() throws RestException {
		SessionWorkerClient client = new SessionWorkerClient(launcher.getUnparseableTokenTarget(Role.SESSION_WORKER), sessionDbClient1, fileBrokerClient1);
		this.testGetAuth(client, 401);
	}
	
	@Test
    public void postUnparseableToken() throws RestException {	
		SessionWorkerClient client = new SessionWorkerClient(launcher.getUnparseableTokenTarget(Role.SESSION_WORKER), sessionDbClient1, fileBrokerClient1);		
		this.testPostAuth(client, 401);
	}
	
	@Test
    public void getNoAuth() throws RestException {
		SessionWorkerClient client = new SessionWorkerClient(launcher.getNoAuthTarget(Role.SESSION_WORKER), sessionDbClient1, fileBrokerClient1);
		this.testGetAuth(client, 401);
	}
	
	@Test
    public void postNoAuth() throws RestException {	
		SessionWorkerClient client = new SessionWorkerClient(launcher.getNoAuthTarget(Role.SESSION_WORKER), sessionDbClient1, fileBrokerClient1);		
		this.testPostAuth(client, 401);
	}
	
	/**
	 * Test that setupExctractionTest() works, because other tests rely on it
	 * 
	 * @throws RestException
	 */
	@Test
    public void setupTest() throws RestException {
				
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
			client.getZipSessionStream(sessionId);
			Assertions.fail();
		} catch (RestException e) {
			assertEquals(expectedStatusCode, e.getResponse().getStatus());
		}
		sessionDbClient1.deleteSession(sessionId);
    }
	
    public void testPostAuth(SessionWorkerClient sessionWorkerClient, int expectedStatusCode) throws RestException {
				
		UUID sessionId1 = sessionDbClient1.createSession(RestUtils.getRandomSession()); 
		UUID sessionId2 = sessionDbClient1.createSession(RestUtils.getRandomSession());
		
		// get a zip stream and upload the zip to a session owned by user1
		UUID zipDatasetId = setupExtactionTest(sessionId1, sessionId2);
					
		try {
			SessionWorkerClient client = new SessionWorkerClient(launcher.getAuthFailTarget(Role.SESSION_WORKER), sessionDbClient1, fileBrokerClient1);
			client.extractZipSession(sessionId1, zipDatasetId);
			Assertions.fail();
		} catch (RestException e) {
			assertEquals(expectedStatusCode, e.getResponse().getStatus());
		}
		
		sessionDbClient1.deleteSession(sessionId1);
		sessionDbClient1.deleteSession(sessionId2);
    }
	
	
	private UUID setupExtactionTest(UUID sourceSession, UUID targetSession) throws RestException {
		
		InputStream zipStream = sessionWorkerClient1.getZipSessionStream(sourceSession);

		Dataset zipDataset = new Dataset();
		UUID zipDatasetId = sessionDbClient1.createDataset(targetSession, zipDataset);
		fileBrokerClient1.upload(targetSession, zipDatasetId, zipStream);
		
		return zipDatasetId;		
	}	
}
