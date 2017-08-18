package fi.csc.chipster.rest;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.Role;

public class AdminResourceTest {

	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;
	private static TestServerLauncher launcher;

	private static Config config;

    @BeforeClass
    public static void setUp() throws Exception {
    	config = new Config();
    	launcher = new TestServerLauncher(config);        
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }
    
    @Test
    public void onlyStatusForNormalUsers() throws IOException {
    	for (String role : config.getServicePasswords().keySet()) {
    		getStatus(role);
    	}
    }
    
    @Test
    public void noAuth() throws IOException {
    	for (String role : config.getServicePasswords().keySet()) {
     		assertEquals(401, getStatusResponse(launcher.getNoAuthClient(), role).getStatus());
     		assertEquals(403, getStatusResponse(launcher.getTokenFailClient(), role).getStatus());
     		assertEquals(401, getStatusResponse(launcher.getUnparseableTokenClient(), role).getStatus());
    	}
    }
    
    @Test 
    public void sessionDbTopics() throws IOException {
    	assertEquals(403, getAdminResponse(launcher.getUser1Client(), Role.SESSION_DB, "topics").getStatus());
    	assertEquals(401, getAdminResponse(launcher.getNoAuthClient(), Role.SESSION_DB, "topics").getStatus());
 		assertEquals(403, getAdminResponse(launcher.getTokenFailClient(), Role.SESSION_DB, "topics").getStatus());
 		assertEquals(401, getAdminResponse(launcher.getUnparseableTokenClient(), Role.SESSION_DB, "topics").getStatus());
    }
    
    public void getStatus(String role) throws IOException {
    	assertEquals(1, getStatusMap(launcher.getUser1Client(), role).size());
    	assertEquals(true, getStatusMap(launcher.getUser1Client(), role).containsKey("status"));
    }
    
	public HashMap<String, Object> getStatusMap(Client client, String role) throws IOException {
    	return getMap(getStatusResponse(client, role));
	}
    
	@SuppressWarnings("unchecked")
    private HashMap<String, Object> getMap(Response response) throws IOException {
		return RestUtils.parseJson(HashMap.class, IOUtils.toString((InputStream)response.getEntity()));
	}

	public Response getStatusResponse(Client client, String role) throws IOException {    	
    	Response response = getAdminResponse(client, role, "status");
    	
    	return response;
	}
    
    public Response getAdminResponse(Client client, String role, String path) throws IOException {    	
    	Response response = getJson(
    			client.target(config.getAdminBindUrl(role))
    			.path("admin")
    			.path(path));
    	
    	return response;
	}
    
    public Response getJson(WebTarget target) {
    	Response response = target
    			.request(JSON)
    		    .get(Response.class);
    	
    	return response;
    }
}
