package fi.csc.chipster.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.RestException;

public class WebServerTest {
	
	private static TestServerLauncher launcher;
	private static WebTarget webServerTarget;

    @BeforeAll
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);

    	// this would return the angular-cli test server now in the dev setup
        //webServerTarget = launcher.getNoAuthTarget(Role.WEB_SERVER);
    	// but we want to test the Java server
        webServerTarget = launcher.getNoAuthClient().target("http://localhost:8000");
    }

    @AfterAll
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
				
    	// in the dev setup the web server is serving just the src folder
    	// the app won't work without the build, but the content is enough for these tests
		Response resp = webServerTarget.path("main.ts").request().get();
		
		assertEquals(200, resp.getStatus());
		
		// check something in the content to make sure this is not the index.html
		String main = IOUtils.toString((InputStream)resp.getEntity(), Charset.defaultCharset());
		assertEquals(true, main.contains("<h1>Chipster web-server</h1>"));
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
		
		String bodyIndex = RestUtils.toString((InputStream)respIndex.getEntity());
		String body404 = RestUtils.toString((InputStream)resp404.getEntity());
		
		assertEquals(bodyIndex, body404);
    }
}
