package fi.csc.chipster.web;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.RestException;

public class WebServerTest {
	
	private static TestServerLauncher launcher;
	private static WebTarget webServerTarget;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);

        webServerTarget = launcher.getNoAuthTarget(Role.WEB_SERVER);
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }
    
    @Test
    public void getRoot() throws RestException, JsonParseException, JsonMappingException, IOException {
				
		Response resp = webServerTarget.request().get();
		
		assertEquals(200, resp.getStatus());
    }
    
    /**
     * Check that the web server can return also other files than the index.html
     * 
     * @throws RestException
     * @throws JsonParseException
     * @throws JsonMappingException
     * @throws IOException
     */
    @Test
    public void getFile() throws RestException, JsonParseException, JsonMappingException, IOException {
				
		Response resp = webServerTarget.path("favicon.ico").request().get();
		
		assertEquals(200, resp.getStatus());
		
		// check the first "magic bytes" to recognize the .ico format
		byte[] ico = IOUtils.toByteArray((InputStream)resp.getEntity());
		assertEquals(true, Arrays.equals(new byte[] {0, 0, 1, 0}, Arrays.copyOfRange(ico, 0, 4)));
    }
    
	/**
	 * Check that the web server responds with index.html when the requested path doesn't exist
	 * 
	 * This loads the Angular app and allows its router to handle the path. 
	 * 
	 * @throws RestException
	 * @throws JsonParseException
	 * @throws JsonMappingException
	 * @throws IOException
	 */
	@Test
    public void getNonExisting() throws RestException, JsonParseException, JsonMappingException, IOException {
		
		Response respIndex = webServerTarget.path("index.html").request().get();
		Response resp404 = webServerTarget.path("not-existing-file").request().get();
		
		assertEquals(200, respIndex.getStatus());
		assertEquals(200, resp404.getStatus());
		
		String bodyIndex = IOUtils.toString((InputStream)respIndex.getEntity());
		String body404 = IOUtils.toString((InputStream)resp404.getEntity());
		
		assertEquals(bodyIndex, body404);
    }
}
