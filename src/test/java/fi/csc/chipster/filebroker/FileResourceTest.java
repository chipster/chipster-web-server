package fi.csc.chipster.filebroker;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

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
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.DatasetResourceTest;
import fi.csc.chipster.sessiondb.SessionResourceTest;

public class FileResourceTest {
	
    private static final String TEST_FILE = "build.gradle";
	private static WebTarget sessionDbTarget1;
    private static WebTarget sessionDbTarget2;
	private static String session1Path;
	private static String session2Path;
	private static TestServerLauncher launcher;
	private static WebTarget fileBrokerTarget1;
	private static WebTarget fileBrokerTarget2;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);

        sessionDbTarget1 = launcher.getUser1Target(Role.SESSION_DB);
        sessionDbTarget2 = launcher.getUser2Target(Role.SESSION_DB);
        
        fileBrokerTarget1 = launcher.getUser1Target(Role.FILE_BROKER);
        fileBrokerTarget2 = launcher.getUser2Target(Role.FILE_BROKER);
        
        session1Path = SessionResourceTest.postRandomSession(sessionDbTarget1);
        session2Path = SessionResourceTest.postRandomSession(sessionDbTarget2);
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }
    
	@Test
    public void putAndChange() throws FileNotFoundException {
		
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);				
		assertEquals(200, uploadFile(fileBrokerTarget1, datasetPath).getStatus());
		
		// not possible to upload a new file, if the dataset has one already
		assertEquals(409, uploadFile(fileBrokerTarget1, datasetPath).getStatus());
    }

	@Test
    public void putWrongUser() throws FileNotFoundException {
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);				
		assertEquals(403, uploadFile(fileBrokerTarget2, datasetPath).getStatus());		
    }
	
	@Test
    public void putAuthFail() throws FileNotFoundException {
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);				
		assertEquals(401, uploadFile(launcher.getAuthFailTarget(Role.FILE_BROKER), datasetPath).getStatus());		
    }
	
	@Test
    public void putTokenFail() throws FileNotFoundException {
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);				
		assertEquals(404, uploadFile(launcher.getTokenFailTarget(Role.FILE_BROKER), datasetPath).getStatus());		
    }
	
	@Test
    public void putUnparseableToken() throws FileNotFoundException {
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);				
		assertEquals(401, uploadFile(launcher.getUnparseableTokenTarget(Role.FILE_BROKER), datasetPath).getStatus());		
    }
	
	@Test
    public void putWrongSession() throws FileNotFoundException {
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);
		datasetPath.replace(session1Path, session2Path);
		assertEquals(401, uploadFile(launcher.getUnparseableTokenTarget(Role.FILE_BROKER), datasetPath).getStatus());		
    }
	
	//@Test
    public void putLargeFile() throws FileNotFoundException {
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);				
		assertEquals(200, uploadInputStream(fileBrokerTarget1, datasetPath, new DummyInputStream(6)).getStatus());		
    }	

	private Response uploadFile(WebTarget target, String datasetPath) throws FileNotFoundException {
        InputStream fileInStream = new FileInputStream(new File(TEST_FILE));
              
        return uploadInputStream(target, datasetPath, fileInStream);
	}
	
	private Response uploadInputStream(WebTarget target, String datasetPath,
			InputStream inputStream) {
        // Use chunked encoding to disable buffering. HttpUrlConnector in 
        // Jersey buffers the whole file before sending it by default, which 
        // won't work with big files.
        target.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
        Response response = target.path(datasetPath).request().put(Entity.entity(inputStream, MediaType.APPLICATION_OCTET_STREAM),Response.class);
        return response;
	}

	class DummyInputStream extends InputStream {
	    
		long bytes = 0;
		private long size;
		
	    public DummyInputStream(long sizeInGb) {
	    	this.size = sizeInGb * 1024 * 1024 * 1024;
		}

		@Override
	    public int read() { 
	    	if (bytes < size) {
	    		bytes++;
	    		return (byte) (bytes % 256);
	    	} else {
	    		return -1;
	    	}
	    }
	}

	@Test
    public void get() throws IOException {
        
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);				
		assertEquals(200, uploadFile(fileBrokerTarget1, datasetPath).getStatus());
		
		InputStream remoteStream = fileBrokerTarget1.path(datasetPath).request().get(InputStream.class);
		InputStream fileStream = new FileInputStream(new File(TEST_FILE));
		
		assertEquals(true, IOUtils.contentEquals(remoteStream, fileStream));		
    }
	
	@Test
    public void getAuthFail() throws IOException {        
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);				
		assertEquals(200, uploadFile(fileBrokerTarget1, datasetPath).getStatus());		
		assertEquals(401, launcher.getAuthFailTarget(Role.FILE_BROKER).path(datasetPath).request().get(Response.class).getStatus());
    }
	
	@Test
    public void getNoAuth() throws IOException {        
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);				
		assertEquals(200, uploadFile(fileBrokerTarget1, datasetPath).getStatus());		
		assertEquals(401, launcher.getNoAuthTarget(Role.FILE_BROKER).path(datasetPath).request().get(Response.class).getStatus());
    }
	
	@Test
    public void getTokenFail() throws IOException {        
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);				
		assertEquals(200, uploadFile(fileBrokerTarget1, datasetPath).getStatus());		
		assertEquals(404, launcher.getTokenFailTarget(Role.FILE_BROKER).path(datasetPath).request().get(Response.class).getStatus());
    }
	
	@Test
    public void getUnparseableToken() throws IOException {        
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);				
		assertEquals(200, uploadFile(fileBrokerTarget1, datasetPath).getStatus());		
		assertEquals(401, launcher.getUnparseableTokenTarget(Role.FILE_BROKER).path(datasetPath).request().get(Response.class).getStatus());
    }
	
	@Test
    public void getWrongSession() throws IOException {        
		String datasetPath = DatasetResourceTest.postRandomDataset(sessionDbTarget1, session1Path);				
		assertEquals(200, uploadFile(fileBrokerTarget1, datasetPath).getStatus());
		datasetPath.replace(session1Path, session2Path);
		assertEquals(401, launcher.getUnparseableTokenTarget(Role.FILE_BROKER).path(datasetPath).request().get(Response.class).getStatus());
    }
}
