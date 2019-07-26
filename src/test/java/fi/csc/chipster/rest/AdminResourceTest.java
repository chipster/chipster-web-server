package fi.csc.chipster.rest;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Set;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.util.Assert;

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
    
    public Set<String> getRolesWithAdminUrl() {
    	return config.getAdminServiceUrls().keySet();
    }
    
    @Test
    public void getStatus() throws IOException {
    	for (String role : getRolesWithAdminUrl()) {
    		if (Role.BACKUP.equals(role)) {
    			// the backup service doesn't offer status information
    			continue;
    		}
    		getStatus(role);
    	}
    }
    
    @Test
    public void getAlive() throws IOException {
    	for (String role : getRolesWithAdminUrl()) {
    		System.out.println(role);
    		// doesn't require authentication
    		assertEquals(200, getAdminResponse(launcher.getNoAuthClient(), role, "alive").getStatus());
    	}
    }
    
    @Test
    public void noAuth() throws IOException {
    	for (String role : getRolesWithAdminUrl()) {
    		if (Role.TYPE_SERVICE.equals(role)) {
    			//FXIME type service doesn't authenticate 
    			continue;
    		}
    		
     		assertEquals(403, getStatusResponse(launcher.getUser1Client(), role).getStatus());
     		assertEquals(403, getStatusResponse(launcher.getNoAuthClient(), role).getStatus());
     		assertEquals(403, getStatusResponse(launcher.getWrongTokenClient(), role).getStatus());
     		assertEquals(401, getStatusResponse(launcher.getUnparseableTokenClient(), role).getStatus());
    	}
    }
    
    @Test 
    public void sessionDbTopics() throws IOException {
    	assertEquals(403, getAdminResponse(launcher.getUser1Client(), Role.SESSION_DB, "topics").getStatus());
    	assertEquals(403, getAdminResponse(launcher.getNoAuthClient(), Role.SESSION_DB, "topics").getStatus());
 		assertEquals(403, getAdminResponse(launcher.getWrongTokenClient(), Role.SESSION_DB, "topics").getStatus());
 		assertEquals(401, getAdminResponse(launcher.getUnparseableTokenClient(), Role.SESSION_DB, "topics").getStatus());
    }
    
    public void getStatus(String role) throws IOException {
    	Assert.notNull(getStatusMap(launcher.getMonitoringClient(), role).size());
    }
    
	public HashMap<String, Object> getStatusMap(Client client, String role) throws IOException {
    	return getMap(getStatusResponse(client, role));
	}
    
	@SuppressWarnings("unchecked")
    private HashMap<String, Object> getMap(Response response) throws IOException {
		return RestUtils.parseJson(HashMap.class, RestUtils.toString((InputStream)response.getEntity()));
	}

	public Response getStatusResponse(Client client, String role) throws IOException {    	
    	Response response = getAdminResponse(client, role, "status");
    	
    	return response;
	}
    
    public Response getAdminResponse(Client client, String role, String path) throws IOException {    	
    	Response response = getJson(
    			client.target(config.getAdminServiceUrls().get(role))
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
