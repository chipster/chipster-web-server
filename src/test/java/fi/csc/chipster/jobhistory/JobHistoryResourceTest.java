
package fi.csc.chipster.jobhistory;

import static org.junit.Assert.assertEquals;

import java.util.UUID;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;

public class JobHistoryResourceTest {

	private static TestServerLauncher launcher;
	//Users with non admin right
	private static SessionDbClient sessionDBClient1;
	private static JobHistoryClient jobHistoryClient1;
	//Users with admin right
	private static JobHistoryClient jobHistoryClient2;
	
	private static UUID sessionID;

	@BeforeClass
	public static void setUp() throws Exception {
		Config config = new Config();
		launcher = new TestServerLauncher(config);

		sessionDBClient1 = new SessionDbClient(launcher.getServiceLocator(),
				launcher.getUser1Token());
		
		jobHistoryClient1= new JobHistoryClient(launcher.getServiceLocator(),
				launcher.getUser1Token());
		
		jobHistoryClient2= new JobHistoryClient(launcher.getServiceLocator(),
				launcher.getAdminToken());
		
		sessionID=sessionDBClient1.createSession(RestUtils.getRandomSession());
	}
	
	@AfterClass
	public static void tearDown() throws Exception{
		launcher.stop();
	}
	
	@Test
	public void get() throws RestException{
		assertEquals(403,jobHistoryClient1.get().getStatus());
		assertEquals(200,jobHistoryClient2.get().getStatus());
	}
	
	@Test
	public void testSaveJob(){
		try {
			UUID jobId=sessionDBClient1.createJob(sessionID, RestUtils.getRandomJob());
			Thread.sleep(100);
			assertEquals(true, jobHistoryClient2.getJobByID(jobId)!=null);
			
		} catch (RestException | InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	
	
}
