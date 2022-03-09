package fi.csc.chipster.auth;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Random;

import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.UserToken;
import fi.csc.chipster.auth.resource.AuthTokenResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.TestServerLauncher;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class AuthTokenResourceTest {

    public static final String PATH_TOKENS = "tokens";
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
    	postUserTokenForClient(target);
    }
    
    @Test
    public void noAuth() throws IOException {
    	// no authorized header
    	assertEquals(403, postTokenResponse(launcher.getNoAuthTarget(Role.AUTH), null, null).getStatus());
    }
    
    @Test
    public void wrongCredentials() throws IOException {
    	assertEquals(403, postTokenResponse(target, TestServerLauncher.UNIT_TEST_USER1, "wrongPasword").getStatus());
    	assertEquals(403, postTokenResponse(target, "wrongUsername", "wrongPasword").getStatus());
    }
    
    @Test
    public void postServer() throws IOException {
    	postUserTokenForSessionDb(target);                          
    }

	@Test
    public void validate() throws IOException {
        String clientToken = postUserTokenForClient(target);
        String serverToken = postUserTokenForSessionDb(target);
        String wrongToken = TestServerLauncher.getWrongKeyToken().getPassword();
        
        getToken(target, "token", serverToken, clientToken);
        
        assertEquals(401, getTokenResponse(target, "token", "unparseableServerToken", clientToken).getStatus());
        assertEquals(403, getTokenResponse(target, "token", wrongToken, clientToken).getStatus());
        assertEquals(404, getTokenResponse(target, "token", serverToken, "unparseableClientToken").getStatus());
        assertEquals(404, getTokenResponse(target, "token", serverToken, wrongToken).getStatus());
        assertEquals(403, getTokenResponse(launcher.getNoAuthTarget(Role.AUTH), null, null, clientToken).getStatus());
    }
	
	@Test
	public void postSessionToken() {
		String clientToken = postUserTokenForClient(target);
        String sessionDbToken = postUserTokenForSessionDb(target);
        String schedulerToken = postUserTokenForScheduler(target);

        // client must not be able to create new tokens
        try {
        	postToken(target.path(PATH_TOKENS).path(AuthTokenResource.PATH_SESSION_TOKEN), 
        			"token", clientToken);
        	assertEquals(true, false);
        } catch (ForbiddenException e) {
        	assertEquals(403, e.getResponse().getStatus());
        }
        
        // session-db must be able to create session tokens
        // SessionDbTokenTest tests that these tokens work
        String sessionToken = postToken(target.path(PATH_TOKENS).path(AuthTokenResource.PATH_SESSION_TOKEN), 
        		"token", sessionDbToken);
        
        // other servers must not create SessionTokens directly from auth,
        // but they can request one from session-db
        try {
        	postToken(target.path(PATH_TOKENS).path(AuthTokenResource.PATH_SESSION_TOKEN), 
        			"token", schedulerToken);
        	assertEquals(true, false);
        } catch (ForbiddenException e) {
        	assertEquals(403, e.getResponse().getStatus());
        }
        
        // SessionTokens must not be allowed to create new tokens (especially directly from auth)
        try {
        	postToken(target.path(PATH_TOKENS).path(AuthTokenResource.PATH_DATASET_TOKEN), 
        			"token", sessionToken);
        	assertEquals(true, false);
        } catch (ForbiddenException e) {
        	assertEquals(403, e.getResponse().getStatus());
        }

        // SessionTokens must not be allowed to create new tokens (especially directly from auth)
        try {
        	postToken(target.path(PATH_TOKENS).path(AuthTokenResource.PATH_SESSION_TOKEN), 
        			"token", sessionToken);
        	assertEquals(true, false);
        } catch (ForbiddenException e) {
        	assertEquals(403, e.getResponse().getStatus());
        }
	}
	
	@Test
	public void postDatasetToken() {
		
		String clientToken = postUserTokenForClient(target);
        String sessionDbToken = postUserTokenForSessionDb(target);
        String schedulerToken = postUserTokenForScheduler(target);

        // client must not be able to create new tokens
        try {
        	postToken(target.path(PATH_TOKENS).path(AuthTokenResource.PATH_DATASET_TOKEN), 
        			"token", clientToken);
        	assertEquals(true, false);
        } catch (ForbiddenException e) {
        	assertEquals(403, e.getResponse().getStatus());
        }
        
        // session-db must be able to create session tokens
        // SessionDbTokenTest tests that these tokens work
        String datasetToken = postToken(target.path(PATH_TOKENS).path(AuthTokenResource.PATH_DATASET_TOKEN), 
        		"token", sessionDbToken);
                
        // other servers must not create SessionTokens directly from auth,
        // but they can request one from session-db
        try {
        	postToken(target.path(PATH_TOKENS).path(AuthTokenResource.PATH_DATASET_TOKEN), 
        			"token", schedulerToken);
        	assertEquals(true, false);
        } catch (ForbiddenException e) {
        	assertEquals(403, e.getResponse().getStatus());
        }
        
        // datasetTokens must not be allowed to create new tokens (especially directly from auth)
        try {
        	postToken(target.path(PATH_TOKENS).path(AuthTokenResource.PATH_DATASET_TOKEN), 
        			"token", datasetToken);
        	assertEquals(true, false);
        } catch (ForbiddenException e) {
        	assertEquals(403, e.getResponse().getStatus());
        }

        // datasetTokens must not be allowed to create new tokens (especially directly from auth)
        try {
        	postToken(target.path(PATH_TOKENS).path(AuthTokenResource.PATH_SESSION_TOKEN), 
        			"token", datasetToken);
        	assertEquals(true, false);
        } catch (ForbiddenException e) {
        	assertEquals(403, e.getResponse().getStatus());
        }
	}
    
	/**
	 * Check that password requests are being throttled 
	 */
	@Test
	public void throttle() {
		
		int i = 0;
		int lastStatus = 0;
		int maxTries = 30;
		
		// test with random username to avoid throttling any real account
		String username = "throttleTestUser" + new Random().nextLong();
		
		for (; i < maxTries; i++) {
			lastStatus = postTokenResponse(target, username, "testPassword").getStatus();
			
			if (lastStatus != 403) {
				break;
			}
		}
		
		// do not throttle right away
		assertEquals(true, i > 10);
		
		// do not allow too many requests without throttling
		assertEquals(true, i < maxTries - 1);
		
		// check the status of the last response
		assertEquals(429, lastStatus);
	}
	
	
    public static String postUserTokenForClient(WebTarget target) {
    	return postUserToken(target, TestServerLauncher.UNIT_TEST_USER1, "clientPassword");
	}
    
    public static String postUserTokenForSessionDb(WebTarget target) {
    	return postUserToken(target, Role.SESSION_DB, Role.SESSION_DB);
	}
    
    public static String postUserTokenForScheduler(WebTarget target) {
    	return postUserToken(target, Role.SCHEDULER, Role.SCHEDULER);
	}

    public static UserToken getToken(WebTarget target, String username, String password, String clientToken) {
    	UserToken token = target
    			.path(PATH_TOKENS)
    			.request(MediaType.APPLICATION_JSON)
    			.property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_USERNAME, username)
    		    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_PASSWORD, password)
    		    .header("chipster-token", clientToken)
    		    .get(UserToken.class);
    	
        assertEquals(false, token == null);
        
        return token;
	}
    
    public static Response deleteTokenResponse(WebTarget target, String username, String password) {
    	Response response = target
    			.path(PATH_TOKENS)
    			.request(JSON)
    			.property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_USERNAME, username)
    		    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_PASSWORD, password)
    		    .delete(Response.class);
    	
        return response;
	}
    
    public static Response getTokenResponse(WebTarget target, String username, String password, String clientToken) {
    	Response response = target
    			.path(PATH_TOKENS)
    			.request(MediaType.APPLICATION_JSON)
    			.property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_USERNAME, username)
    		    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_PASSWORD, password)
    		    .header("chipster-token", clientToken)
    		    .get(Response.class);
    	
        return response;
	}
    
    public static String postUserToken(WebTarget target, String username, String password) {
    	
    	return postToken(target.path(PATH_TOKENS), username, password);
	}
    
    public static String postToken(WebTarget target, String username, String password) {
    	String token = target
    			.request(MediaType.TEXT_PLAIN)
    			.property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_USERNAME, username)
    		    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_PASSWORD, password)
    		    .post(null, String.class);
    	
        assertEquals(false, token == null);
        
        return token;
	}
    
    public static Response postTokenResponse(WebTarget target, String username, String password) {
    	Response response = target
    			.path(PATH_TOKENS)
    			.request(MediaType.TEXT_PLAIN)
    			.property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_USERNAME, username)
    		    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_BASIC_PASSWORD, password)
    		    .post(null, Response.class);
    	
        return response;
	}	
}
