package fi.csc.chipster.filebroker;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.UUID;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.client.ClientProperties;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;

public class FileResourceTest {
	
    private static final String TEST_FILE = "build.gradle";
	private static TestServerLauncher launcher;
	private static WebTarget fileBrokerTarget1;
	private static WebTarget fileBrokerTarget2;
	private static SessionDbClient sessionDbClient1;
	private static SessionDbClient sessionDbClient2;
	private static UUID sessionId1;
	private static UUID sessionId2;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);

    	sessionDbClient1 = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token());
    	sessionDbClient2 = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser2Token());
		
        fileBrokerTarget1 = launcher.getUser1Target(Role.FILE_BROKER);
        fileBrokerTarget2 = launcher.getUser2Target(Role.FILE_BROKER);
        
        sessionId1 = sessionDbClient1.createSession(RestUtils.getRandomSession());
        sessionId2 = sessionDbClient2.createSession(RestUtils.getRandomSession());   
    }

    @AfterClass
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
		
		long chunk1Length = 10*1024*1024;
		long chunk2Length = 1*1024*1024;
				
		InputStream chunk1Stream = new DummyInputStream(chunk1Length);
		InputStream chunk1StreamRef = new DummyInputStream(chunk1Length);
		InputStream chunk2Stream = new DummyInputStream(chunk2Length);
		@SuppressWarnings("resource")
		InputStream chunk2StreamRef = new DummyInputStream(chunk2Length);		
		
		WebTarget target = getChunkedTarget(fileBrokerTarget1, sessionId1, datasetId)
			.queryParam("flowChunkNumber", "1")
			.queryParam("flowChunkSize", "" + chunk1Length)
			.queryParam("flowCurrentChunkSize", "" + chunk1Length)
			.queryParam("flowTotalSize", "" + (chunk1Length + chunk2Length))
			.queryParam("flowIdentifier", "JUnit-test-flow")
			.queryParam("flowFilename", "JUnit-test-flow")
			.queryParam("flowRelativePath", "JUnit-test-flow")
			.queryParam("flowTotalChunks", "2");
		assertEquals(204, putInputStream(target, chunk1Stream).getStatus());
		
		target = getChunkedTarget(fileBrokerTarget1, sessionId1, datasetId)
			.queryParam("flowChunkNumber", "2")
			.queryParam("flowChunkSize", "" + chunk1Length) // default chunk size, but
			.queryParam("flowCurrentChunkSize", "" + chunk2Length) // the last can be different
			.queryParam("flowTotalSize", "" + (chunk1Length + chunk2Length))
			.queryParam("flowIdentifier", "JUnit-test-flow")
			.queryParam("flowFilename", "JUnit-test-flow")
			.queryParam("flowRelativePath", "JUnit-test-flow")
			.queryParam("flowTotalChunks", "2");
		assertEquals(204, putInputStream(target, chunk2Stream).getStatus());
			
		InputStream remoteStream = fileBrokerTarget1.path(getDatasetPath(sessionId1, datasetId)).request().get(InputStream.class);
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
		assertEquals(403, uploadFile(launcher.getTokenFailTarget(Role.FILE_BROKER), sessionId1, datasetId).getStatus());		
    }
	
	@Test
    public void putUnparseableToken() throws FileNotFoundException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());				
		assertEquals(401, uploadFile(launcher.getUnparseableTokenTarget(Role.FILE_BROKER), sessionId1, datasetId).getStatus());		
    }
	
	@Test
    public void putWrongSession() throws FileNotFoundException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());				
		assertEquals(403, uploadFile(fileBrokerTarget1, sessionId2, datasetId).getStatus());		
    }
	
	//@Test
    public void putLargeFile() throws FileNotFoundException, RestException {
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());				
		assertEquals(200, uploadInputStream(fileBrokerTarget1, sessionId1, datasetId, new DummyInputStream(6*1024*1024*1024)).getStatus());		
    }	

	private Response uploadFile(WebTarget target, UUID sessionId, UUID datasetId) throws FileNotFoundException {
        InputStream fileInStream = new FileInputStream(new File(TEST_FILE));
              
        return uploadInputStream(target, sessionId, datasetId, fileInStream);
	}
	
	public static Response uploadInputStream(WebTarget target, UUID sessionId, UUID datasetId, InputStream inputStream) {
		WebTarget chunkedTarget = getChunkedTarget(target, sessionId, datasetId);
        return putInputStream(chunkedTarget, inputStream);
	}

	private static Response putInputStream(WebTarget chunkedTarget, InputStream inputStream) {
		return chunkedTarget.request().put(Entity.entity(inputStream, MediaType.APPLICATION_OCTET_STREAM), Response.class);
	}

	private static WebTarget getChunkedTarget(WebTarget target, UUID sessionId, UUID datasetId) {
        // Use chunked encoding to disable buffering. HttpUrlConnector in 
        // Jersey buffers the whole file before sending it by default, which 
        // won't work with big files.
        target.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
        return target.path(getDatasetPath(sessionId, datasetId));
	}
	
	

	private static String getDatasetPath(UUID sessionId, UUID datasetId) {
		return "sessions/" + sessionId.toString() + "/datasets/" + datasetId.toString();
	}

	class DummyInputStream extends InputStream {
	    
		long bytes = 0;
		private long size;
		
	    public DummyInputStream(long size) {
	    	this.size = size;
		}

		@Override
	    public int read() { 
	    	if (bytes < size) {
	    		bytes++;
	    		// don't convert to byte, because it would be signed and this could return -1 making the stream shorter
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
		
		InputStream remoteStream = fileBrokerTarget1.path(getDatasetPath(sessionId1, datasetId)).request().get(InputStream.class);
		InputStream fileStream = new FileInputStream(new File(TEST_FILE));
		
		assertEquals(true, IOUtils.contentEquals(remoteStream, fileStream));
		
		// check that file-broker has set the correct size for the dataset
		Dataset dataset = sessionDbClient1.getDataset(sessionId1, datasetId);
		assertEquals(new File(TEST_FILE).length(), dataset.getFile().getSize());
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

		// we should be able to read both datasets, although we haven't uploaded the second dataset
		checkFile(sessionId1, datasetId);
		checkFile(sessionId1, datasetId2);
		
		// remove the original dataset
		sessionDbClient1.deleteDataset(sessionId1, datasetId);
		
		// the first dataset must not work anymore
		try {
			checkFile(sessionId1, datasetId);
			assertEquals(true, false);
		} catch (NotFoundException e) {}
		
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
		File storageFile = new File("storage", file.getFileId().toString());
		assertEquals(true, storageFile.exists());
		
		// remove the dataset
		sessionDbClient1.deleteDataset(sessionId1, datasetId);
		
		
		// wait a while and check that the file is removed also
		Thread.sleep(100);
		assertEquals(false, storageFile.exists());
    }
	
	private void checkFile(UUID sessionId, UUID datasetId) throws IOException {
		InputStream remoteStream = fileBrokerTarget1.path(getDatasetPath(sessionId, datasetId)).request().get(InputStream.class);
		InputStream fileStream = new FileInputStream(new File(TEST_FILE));
		assertEquals(true, IOUtils.contentEquals(remoteStream, fileStream));
	}

	@Test
    public void getAuthFail() throws IOException, RestException {        
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());				
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());		
		assertEquals(401, launcher.getAuthFailTarget(Role.FILE_BROKER).path(getDatasetPath(sessionId1, datasetId)).request().get(Response.class).getStatus());
    }
	
	@Test
    public void getNoAuth() throws IOException, RestException {        
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());				
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());		
		assertEquals(401, launcher.getNoAuthTarget(Role.FILE_BROKER).path(getDatasetPath(sessionId1, datasetId)).request().get(Response.class).getStatus());
    }
	
	@Test
    public void getTokenFail() throws IOException, RestException {        
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());				
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());		
		assertEquals(403, launcher.getTokenFailTarget(Role.FILE_BROKER).path(getDatasetPath(sessionId1, datasetId)).request().get(Response.class).getStatus());
    }
	
	@Test
    public void getUnparseableToken() throws IOException, RestException {        
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());				
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());		
		assertEquals(401, launcher.getUnparseableTokenTarget(Role.FILE_BROKER).path(getDatasetPath(sessionId1, datasetId)).request().get(Response.class).getStatus());
    }
	
	@Test
    public void getWrongSession() throws IOException, RestException {        
		UUID datasetId = sessionDbClient1.createDataset(sessionId1, RestUtils.getRandomDataset());				
		assertEquals(204, uploadFile(fileBrokerTarget1, sessionId1, datasetId).getStatus());
		assertEquals(403, fileBrokerTarget1.path(getDatasetPath(sessionId2, datasetId)).request().get(Response.class).getStatus());
    }
}
