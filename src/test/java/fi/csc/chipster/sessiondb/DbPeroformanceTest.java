package fi.csc.chipster.sessiondb;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.websocket.DeploymentException;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;

public class DbPeroformanceTest {

	private static TestServerLauncher launcher;
	private static int n = 10;
	private static Queue<UUID> ids;
	private static SessionDbClient client;
	
	@BeforeClass
	public static void setUpBeforeClass() throws ServletException, DeploymentException, RestException, InterruptedException, IOException, URISyntaxException {
		// once per class
		Config config = new Config();
    	launcher = new TestServerLauncher(config);

    	client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token());
		ids = postManyParallel();
	}
	
	@AfterClass
	public static void tearDownAfterClass() throws IOException, InterruptedException {
		launcher.stop();
	}
    
    @Test
    public void postOne() throws RestException {
    	client.createSession(RestUtils.getRandomSession());
    }
    
    @Test
    public void postMany() throws RestException {
    	for (int i = 0; i < n ; i++) {
    		client.createSession(RestUtils.getRandomSession());
    	}
    }
    
    @Test
    public void postManyParallelTest() throws IOException, InterruptedException {
    	postManyParallel();
    }
    
    public static Queue<UUID> postManyParallel() throws IOException, InterruptedException {
    	
    	final Queue<UUID> ids = new ConcurrentLinkedQueue<>();
    	ExecutorService executor = Executors.newFixedThreadPool(10);
    	for (int i = 0; i < n ; i++) {
    		executor.submit(new Runnable() {
				@Override
				public void run() {
					try {
						ids.add(client.createSession(RestUtils.getRandomSession()));
					} catch (RestException e) {
						e.printStackTrace();
					}		
				}
			});
    	}
    	executor.shutdown();
    	boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
    	assertEquals(false, timeout);
    	return ids;
    }
    
    @Test
    public void getManyParallel() throws IOException, InterruptedException {
    	ExecutorService executor = Executors.newFixedThreadPool(10);
    	for (final UUID id : ids) {
    		executor.submit(new Runnable() {
				@Override
				public void run() {
					try {
						client.getSession(id);
					} catch (RestException e) {
						e.printStackTrace();
					}
				}
			});
    	}
    	executor.shutdown();
    	boolean timeout = !executor.awaitTermination(60, TimeUnit.SECONDS);
    	assertEquals(false, timeout);
    }
    
    @Test
    public void getMany() throws RestException {
    	for (UUID id : ids) {
    		client.getSession(id);
    	}
    }	
}
