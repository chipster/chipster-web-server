package fi.csc.chipster.servicelocator;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.servicelocator.resource.ServiceResource;

public class ServiceResourceTest {

    public static final String PATH_SERVICES = ServiceResource.PATH_SERVICES;
    public static final String PATH_INTERNAL = ServiceResource.PATH_SERVICES + "/" + ServiceResource.PATH_INTERNAL;
	
	private static TestServerLauncher launcher;
	private static WebTarget user1Target;
	private static WebTarget tokenFailTarget;
	private static WebTarget authFailTarget;
	private static WebTarget noAuthTarget;
	private static WebTarget unparseableTokenTarget;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	launcher = new TestServerLauncher(config);
        
        noAuthTarget = launcher.getNoAuthTarget(Role.SERVICE_LOCATOR);
        user1Target = launcher.getUser1Target(Role.SERVICE_LOCATOR);        
        
        unparseableTokenTarget = launcher.getUnparseableTokenTarget(Role.SERVICE_LOCATOR);
        tokenFailTarget = launcher.getWrongTokenTarget(Role.SERVICE_LOCATOR);
        authFailTarget = launcher.getAuthFailTarget(Role.SERVICE_LOCATOR);
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }
	
	@Test
    public void getPublic() throws IOException {
						
		ServiceLocatorClient client = new ServiceLocatorClient(new Config());
		
		Set<String> roles = client.getPublicServices().stream()
				.map(s -> s.getRole())
				.collect(Collectors.toSet());		
		
		// everything else than publicUri must be null
		for (Service s : client.getPublicServices()) {			
			assertEquals(null, s.getAdminUri());
			assertEquals(null, s.getUri());
		}
		
		assertEquals(true, roles.contains(Role.AUTH));
		assertEquals(true, roles.contains(Role.SESSION_DB));
		assertEquals(true, roles.contains(Role.SESSION_DB_EVENTS));
		assertEquals(true, roles.contains(Role.FILE_BROKER));
		assertEquals(true, roles.contains(Role.SESSION_WORKER));		
		        
        assertEquals(401, get(unparseableTokenTarget, PATH_SERVICES));
        assertEquals(403, get(tokenFailTarget, PATH_SERVICES));
        assertEquals(403, get(authFailTarget, PATH_SERVICES));
    }
	
	@Test
    public void getInternal() throws IOException {
						
		ServiceLocatorClient client = launcher.getServiceLocatorForAdmin();
		
		Set<String> roles = client.getInternalServices().stream()
				.map(s -> s.getRole())
				.collect(Collectors.toSet());		
		
		assertEquals(true, roles.contains(Role.AUTH));
		assertEquals(true, roles.contains(Role.SESSION_DB));
		assertEquals(true, roles.contains(Role.SESSION_DB_EVENTS));
		assertEquals(true, roles.contains(Role.FILE_BROKER));
		assertEquals(true, roles.contains(Role.SESSION_WORKER));		
		
		assertEquals(403, get(noAuthTarget, PATH_INTERNAL));
		assertEquals(403, get(user1Target, PATH_INTERNAL));
        assertEquals(401, get(unparseableTokenTarget, PATH_INTERNAL));
        assertEquals(403, get(tokenFailTarget, PATH_INTERNAL));
        assertEquals(403, get(authFailTarget, PATH_INTERNAL));
    }
	
	public static int get(WebTarget target, String path) {
		return target.path(path).request().get(Response.class).getStatus();
	}
}
