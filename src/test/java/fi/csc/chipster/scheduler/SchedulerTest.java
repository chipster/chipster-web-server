package fi.csc.chipster.scheduler;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.client.WebTarget;

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
import fi.csc.chipster.rest.websocket.WebSocketClient;
import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketErrorException;
import fi.csc.chipster.scheduler.JobCommand;
import fi.csc.chipster.scheduler.JobCommand.Command;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.EventTest;
import fi.csc.chipster.sessiondb.JobResourceTest;
import fi.csc.chipster.sessiondb.SessionResourceTest;

public class SchedulerTest {
	
	@SuppressWarnings("unused")
	private final Logger logger = LogManager.getLogger();
		
    private static WebTarget user1Target;
	private static String session1Path;
	private static TestServerLauncher launcher;
	private static String uri;

	private static String token;
	private static String userToken;

    @BeforeClass
    public static void setUp() throws Exception {
    	Config config = new Config();    	

    	launcher = new TestServerLauncher(config, Role.SESSION_DB);  
        user1Target = launcher.getUser1Target();        
        session1Path = SessionResourceTest.postRandomSession(user1Target);        	
		
		ServiceLocatorClient serviceLocator = new ServiceLocatorClient(new Config());
		token = new AuthenticationClient(serviceLocator, "comp", "compPassword").getToken().toString();
		userToken = new AuthenticationClient(serviceLocator, "client", "clientPassword").getToken().toString();
		uri = serviceLocator.get(Role.SCHEDULER).get(0) + "events";
    }

    @AfterClass
    public static void tearDown() throws Exception {
    	launcher.stop();
    }           
    
    @Test
    public void connect() throws Exception {
    	
    	ArrayList<String> messages = new ArrayList<>(); 
    	CountDownLatch latch = new CountDownLatch(1);    	
    	
    	// wrong user
    	try {       
    		EventTest.getTestClient(uri, messages, latch, false, userToken);
    		assertEquals(true, false);
    	} catch (WebSocketErrorException e) {
    	}
    	
    	// unparseable token
    	try {       
    		EventTest.getTestClient(uri, messages, latch, false, "unparseableToken");
    		assertEquals(true, false);
    	} catch (WebSocketErrorException e) {
    	}
    	
    	// wrong token
    	try {       
    		EventTest.getTestClient(uri, messages, latch, false, RestUtils.createId());
    		assertEquals(true, false);
    	} catch (WebSocketErrorException e) {
    	}    

    	// no token
    	try {       
    		EventTest.getTestClient(uri, messages, latch, false, null);
    		assertEquals(true, false);
    	} catch (WebSocketErrorException e) {
    	}
    }
    
    @Test
    public void ping() throws Exception {
    	ArrayList<String> messages = new ArrayList<>(); 
    	CountDownLatch latch = new CountDownLatch(1);
    	
    	// correct settings
    	WebSocketClient client = EventTest.getTestClient(uri, messages, latch, false, token);
    	client.ping();
    	client.shutdown();
    }

    /**
	 * Test that the scheduler sends scheduler messages, when a new job is
	 * created in the session-db
     * @throws Exception 
	 */
    @Test
    public void schedule() throws Exception {    
        
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = EventTest.getTestClient(uri, messages, latch, false, token);
    	
    	String jobPath = JobResourceTest.postRandomJob(user1Target, session1Path);
    	
        // wait for the message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));        
        JobCommand cmd = RestUtils.parseJson(JobCommand.class, messages.get(0));
                       
        assertEquals(Command.SCHEDULE, cmd.getCommand());
        assertEquals(RestUtils.basename(session1Path), cmd.getSessionId().toString());
        assertEquals(RestUtils.basename(jobPath), cmd.getJobId().toString());
        assertEquals(null, cmd.getCompId());
        
        client.shutdown();
    }
    
    /**
     * Test the complete Job scheduling process 
	 * <li>post a job to session-db</li>
	 * <li>wait for schedule command from scheduler</li> 
	 * <li>reply with offer</li>
	 * <li>wait for choose command from scheduler</li>
     * @throws Exception 
     */
    @Test
    public void choose() throws Exception {
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = EventTest.getTestClient(uri, messages, latch, false, token);
    	
    	String jobPath = JobResourceTest.postRandomJob(user1Target, session1Path);
    	
        // wait for the schedule message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));        
        JobCommand cmd = RestUtils.parseJson(JobCommand.class, messages.get(0));                       
        
        /*
         * TestClient can wait only once, but we can create a new one to wait 
         * for the next message.
         */
    	final ArrayList<String> messages2 = new ArrayList<>(); 
    	final CountDownLatch latch2 = new CountDownLatch(1);    	
    	WebSocketClient client2 = EventTest.getTestClient(uri, messages2, latch2, false, token);
        
        // imaginary comp responds with offer
        UUID compId = RestUtils.createUUID();
        client.sendText(RestUtils.asJson(new JobCommand(cmd.getSessionId(), cmd.getJobId(), compId, Command.OFFER)));
        
        // wait for the offer message
        assertEquals(true, latch2.await(1, TimeUnit.SECONDS));        
        JobCommand cmd2 = RestUtils.parseJson(JobCommand.class, messages2.get(0));      
        
        // scheduler responds with a choose message
        assertEquals(Command.CHOOSE, cmd2.getCommand());
        assertEquals(RestUtils.basename(session1Path), cmd2.getSessionId().toString());
        assertEquals(RestUtils.basename(jobPath), cmd2.getJobId().toString());
        assertEquals(compId, cmd2.getCompId());
        
        client.shutdown();
        client2.shutdown();    
    }
    
    @Test
    public void chooseFromMany() throws Exception {
    	
    	final ArrayList<String> messages = new ArrayList<>(); 
    	final CountDownLatch latch = new CountDownLatch(1);    	
    	WebSocketClient client = EventTest.getTestClient(uri, messages, latch, false, token);
    	
    	JobResourceTest.postRandomJob(user1Target, session1Path);
    	
        // wait for the schedule message
        assertEquals(true, latch.await(1, TimeUnit.SECONDS));        
        JobCommand cmd = RestUtils.parseJson(JobCommand.class, messages.get(0));                       
        
        /*
         * TestClient can wait only once, but we can create a new one to wait 
         * for the next message.
         */
    	final ArrayList<String> messages2 = new ArrayList<>(); 
    	final CountDownLatch latch2 = new CountDownLatch(1);    	
    	WebSocketClient client2 = EventTest.getTestClient(uri, messages2, latch2, false, token);
        
        // imaginary comps respond with offer
    	HashSet<UUID> compIds = new HashSet<>();
    	for (int i = 0; i < 10; i++) {
    		UUID compId = RestUtils.createUUID();
    		client.sendText(RestUtils.asJson(new JobCommand(cmd.getSessionId(), cmd.getJobId(), compId, Command.OFFER)));
    		compIds.add(compId);
    	}
        // wait for the offer message
        assertEquals(true, latch2.await(1, TimeUnit.SECONDS));
        JobCommand cmd2 = RestUtils.parseJson(JobCommand.class, messages2.get(0));              
        assertEquals(true, compIds.contains(cmd2.getCompId()));

        // wait a little bit more to make sure that there is no more messages
        Thread.sleep(100);
        assertEquals(1, messages2.size());             
        
        client.shutdown();
        client2.shutdown();
    }	
	
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
