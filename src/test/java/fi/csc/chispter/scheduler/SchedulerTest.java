package fi.csc.chispter.scheduler;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.DeploymentException;
import javax.websocket.MessageHandler.Whole;
import javax.ws.rs.client.WebTarget;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.rest.WebsocketClient;
import fi.csc.chipster.scheduler.JobCommand;
import fi.csc.chipster.scheduler.JobCommand.Command;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.JobResourceTest;
import fi.csc.chipster.sessiondb.SessionResourceTest;

public class SchedulerTest {
	
	private final Logger logger = LogManager.getLogger();
		
    private static WebTarget user1Target;
	private static String session1Path;
	private static TestServerLauncher launcher;
	private static String uri;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();
    	// hide info level messages about websocket connection status 
    	config.setLoggingLevel("fi.csc.chipster", Level.WARN);
    	launcher = new TestServerLauncher(config, Role.SESSION_DB);  
        user1Target = launcher.getUser1Target();        
        session1Path = SessionResourceTest.postRandomSession(user1Target);        	
		
		ServiceLocatorClient serviceLocator = new ServiceLocatorClient(new Config());
		AuthenticationClient auth = new AuthenticationClient(serviceLocator, "comp", "compPassword");
		String tokenKey = auth.getToken().toString();		
		uri = serviceLocator.get(Role.SCHEDULER).get(0) + "events?token=" + tokenKey;
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }           
    
    @Test
    public void connect() throws DeploymentException, IOException, URISyntaxException, InterruptedException {
    	WebsocketClient client = new WebsocketClient(uri, null);
    	client.shutdown();
    }

    /**
	 * Test that the scheduler sends scheduler messages, when a new job is
	 * created in the session-db
	 * 
	 * @throws DeploymentException
	 * @throws IOException
	 * @throws URISyntaxException
	 * @throws InterruptedException
	 */
    @Test
    public void schedule() throws DeploymentException, IOException, URISyntaxException, InterruptedException {
    	final CountDownLatch latch = new CountDownLatch(1);
    	WebsocketClient client = new WebsocketClient(uri, new Whole<String>() {			
			@Override
			public void onMessage(String message) {
				JobCommand cmd = RestUtils.parseJson(JobCommand.class, message);
				// it would be good to check the jobId, but it's not easy to 
				// get it here
				if (
						threadSafeAssertEquals(Command.SCHEDULE, cmd.getCommand()) &&
						threadSafeAssertEquals(RestUtils.basename(session1Path), cmd.getSessionId().toString())) {
					latch.countDown();
				}
			}
		});
    	JobResourceTest.postRandomJob(user1Target, session1Path);
    	assertEquals(true, latch.await(1, TimeUnit.SECONDS));
    	client.shutdown();
    }
    
	private boolean threadSafeAssertEquals(Object o1, Object o2) {
		if (!o1.equals(o2)) {
			logger.error("assert failed, was: " + o2 + "(" + o1.getClass().getSimpleName() + "), expected: " + o1 + "(" + o2.getClass().getSimpleName() + ")");
			return false;
		}
		return true;
	}
    
    /**
     * Test the complete Job scheduling process 
	 * <li>post a job to session-db</li>
	 * <li>wait for schedule command from scheduler</li> 
	 * <li>reply with offer</li>
	 * <li>wait for choose command from scheduler</li>
	 * 
     * 
     * @throws DeploymentException
     * @throws IOException
     * @throws URISyntaxException
     * @throws InterruptedException
     */
    @Test
    public void choose() throws DeploymentException, IOException, URISyntaxException, InterruptedException {
    	final CountDownLatch latch = new CountDownLatch(1);
    	final ConcurrentLinkedQueue<UUID> chosenComps = new ConcurrentLinkedQueue<>();
    	WebsocketClient client = createCompMock(chosenComps, latch);
    	JobResourceTest.postRandomJob(user1Target, session1Path);
    	assertEquals(true, latch.await(1, TimeUnit.SECONDS));
    	assertEquals(1, chosenComps.size());
    	client.shutdown();
    }
    
    @Test
    public void chooseFromMany() throws DeploymentException, IOException, URISyntaxException, InterruptedException {
    	int count = 10;
    	final CountDownLatch latch = new CountDownLatch(count);
    	final ConcurrentLinkedQueue<UUID> chosenComps = new ConcurrentLinkedQueue<>();
    	ArrayList<WebsocketClient> clients = new ArrayList<>();
    	for (int i = 0; i < count; i++) {
    		clients.add(createCompMock(chosenComps, latch));
    	}
    	JobResourceTest.postRandomJob(user1Target, session1Path);
    	assertEquals(true, latch.await(5, TimeUnit.SECONDS));
    	assertEquals(1, chosenComps.size());
    	for (WebsocketClient client : clients) {
    		client.shutdown();
    	}
    }
    
    private WebsocketClient createCompMock(final ConcurrentLinkedQueue<UUID> chosenComps, final CountDownLatch latch) throws DeploymentException, IOException, URISyntaxException, InterruptedException {
    	final UUID serverId = RestUtils.createUUID();
    	final WebsocketClient client = new WebsocketClient();
    	client.connect(uri, new Whole<String>() {			
			@Override
			public void onMessage(String message) {				
				JobCommand cmd = RestUtils.parseJson(JobCommand.class, message);
				logger.debug("got command: " + message);
				if (Command.SCHEDULE == cmd.getCommand()) {
					try {
						// client may not be completely initialized yet, but I guess this is good enough for a test
						client.sendText(RestUtils.asJson(new JobCommand(cmd.getSessionId(), cmd.getJobId(), serverId, Command.OFFER)));
					} catch (InterruptedException | IOException e) {
						logger.error("failed to send an offer", e);
					}
				} else if (
							threadSafeAssertEquals(Command.CHOOSE, cmd.getCommand()) &&
							threadSafeAssertEquals(RestUtils.basename(session1Path), cmd.getSessionId().toString())) {

					if (serverId.equals(cmd.getCompId())) {
						chosenComps.add(serverId);
					}
					latch.countDown();
				}
			}
		});
    	return client;
	}
		
//	public SchedulerTest() {
//
//		try {
//			
//			Object replyRemote = RestUtils.createWebsocketClient(uri, null);
//			RestUtils.waitForShutdown("scheduler test", null);
//
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
//	}
//
//	public static void main(String [] args){
//		
//		new SchedulerTest();		
//	}
	
//	@Override
//	public void onMessage(String message) {
//		
//		JobCommand schedulerMsg = RestUtils.parseJson(JobCommand.class, message);
//		
//		switch (schedulerMsg.getCommand()) {
//		case SCHEDULE:
//			logger.info("received a schedule message for a job " + schedulerMsg.getJobId());
//			try {
//				logger.info("send an offer");
//				this.replyRemote.sendText(RestUtils.asJson(new JobCommand(schedulerMsg.getSessionId(), schedulerMsg.getJobId(), serverId, Command.OFFER )));
//			} catch (IOException e) {
//				logger.error("unable to send an offer", e);
//			}
//			break;
//		case CHOOSE:
//			if (serverId.equals(schedulerMsg.getCompId())) {				
//				logger.info("offer chosen, running the job...");
//			} else {
//				logger.info("offer rejected");
//			}
//			break;
//			
//		case CANCEL:
//			logger.info("cancelling the job...");
//			break;
//
//		default:
//			logger.warn("unknown command: " + schedulerMsg.getCommand());
//			break;
//		}
//	}
}
