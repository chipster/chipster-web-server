package fi.csc.chipster.auth;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.ParsedToken;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.TestServerLauncher;

public class AuthenticationResourceTest {

    public static final String path = "tokens";
	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;
	private static TestServerLauncher launcher;
	private static WebTarget target;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
        
        // client with authentication enabled, but each test will set the credentials later
        target = AuthenticationClient.getClient(null, null, true).target(config.getInternalServiceUrls().get(Role.AUTH));
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }

    @Test
    public void correctPassword() throws IOException {
    	postClientToken(target);
    }
    
    @Test
    public void noAuth() throws IOException {
    	// no authorized header
    	assertEquals(403, postTokenResponse(launcher.getNoAuthTarget(Role.AUTH), null, null).getStatus());
    }
    
    @Test
    public void wrongCredentials() throws IOException {
    	assertEquals(403, postTokenResponse(target, "client", "wrongPasword").getStatus());
    	assertEquals(403, postTokenResponse(target, "wrongUsername", "wrongPasword").getStatus());
    }
    
    @Test
    public void postServer() throws IOException {
    	postServerToken(target);                          
    }

	@Test
    public void validate() throws IOException {
        String clientToken = postClientToken(target);
        String serverToken = postServerToken(target);
        String wrongToken = TestServerLauncher.getWrongKeyToken().getPassword();
        
        getToken(target, "token", serverToken, clientToken);
        
        assertEquals(401, getTokenResponse(target, "token", "unparseableServerToken", clientToken).getStatus());
        assertEquals(403, getTokenResponse(target, "token", wrongToken, clientToken).getStatus());
        assertEquals(404, getTokenResponse(target, "token", serverToken, "unparseableClientToken").getStatus());
        assertEquals(404, getTokenResponse(target, "token", serverToken, wrongToken).getStatus());
        assertEquals(403, getTokenResponse(launcher.getNoAuthTarget(Role.AUTH), null, null, clientToken).getStatus());
    }
    
    public static String postClientToken(WebTarget target) {
    	return postToken(target, "client", "clientPassword");
	}
    
    public static String postServerToken(WebTarget target) {
    	return postToken(target, Role.SESSION_DB, Role.SESSION_DB);
	}

    public static ParsedToken getToken(WebTarget target, String username, String password, String clientToken) {
    	ParsedToken token = target
    			.path(path)
    			.request(MediaType.APPLICATION_JSON)
    			.property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_USERNAME, username)
    		    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_PASSWORD, password)
    		    .header("chipster-token", clientToken)
    		    .get(ParsedToken.class);
    	
        assertEquals(false, token == null);
        
        return token;
	}
    
    public static Response deleteTokenResponse(WebTarget target, String username, String password) {
    	Response response = target
    			.path(path)
    			.request(JSON)
    			.property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_USERNAME, username)
    		    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_PASSWORD, password)
    		    .delete(Response.class);
    	
        return response;
	}
    
    public static Response getTokenResponse(WebTarget target, String username, String password, String clientToken) {
    	Response response = target
    			.path(path)
    			.request(MediaType.APPLICATION_JSON)
    			.property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_USERNAME, username)
    		    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_PASSWORD, password)
    		    .header("chipster-token", clientToken)
    		    .get(Response.class);
    	
        return response;
	}
    
    public static String postToken(WebTarget target, String username, String password) {
    	String token = target
    			.path(path)
    			.request(MediaType.TEXT_PLAIN)
    			.property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_USERNAME, username)
    		    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_PASSWORD, password)
    		    .post(null, String.class);
    	
        assertEquals(false, token == null);
        
        return token;
	}
    
    public static Response postTokenResponse(WebTarget target, String username, String password) {
    	Response response = target
    			.path(path)
    			.request(MediaType.TEXT_PLAIN)
    			.property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_USERNAME, username)
    		    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_PASSWORD, password)
    		    .post(null, Response.class);
    	
        return response;
	}	
}
