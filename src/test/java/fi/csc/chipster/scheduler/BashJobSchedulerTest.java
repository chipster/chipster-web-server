package fi.csc.chipster.scheduler;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.SessionDbClient;

public class BashJobSchedulerTest {

	// this tool must have a "IMAGE" in SADL, otherwise were are testing only
	// OfferJobScheduler
	@SuppressWarnings("unused")
	private static final String BASH_TOOL_ID = "test-data-in-out-image.py";

	@SuppressWarnings("unused")
	private final Logger logger = LogManager.getLogger();

	private static TestServerLauncher launcher;

	private static SessionDbClient user1Client;
	@SuppressWarnings("unused")
	private static UUID sessionId1;

	@BeforeAll
	public static void setUp() throws Exception {
		Config config = new Config();

		launcher = new TestServerLauncher(config);
		user1Client = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);
		sessionId1 = user1Client.createSession(RestUtils.getRandomSession());
	}

	@AfterAll
	public static void tearDown() throws Exception {
		user1Client.close();
		launcher.stop();
	}

	// commit a tool for this test first
	// /**
	// * Test that bash job scheduler run new jobs
	// * @throws Exception
	// */
	// @Test
	// public void run() throws Exception {
	//
	// CountDownLatch latch = new CountDownLatch(1);
	//
	// user1Client.subscribe(sessionId1.toString(), (sessionEvent) -> {
	//
	// if (sessionEvent.getType() == EventType.UPDATE
	// && !JobState.NEW.name().equals(sessionEvent.getState())) {
	//
	//// logger.info("received event: " + RestUtils.asJson(sessionEvent));
	// latch.countDown();
	// }
	// }, "bash-job-scheduler-test");
	//
	//
	// Job job = RestUtils.getRandomJob();
	//
	// logger.info(RestUtils.asJson(job));
	//
	// job.setToolId(BASH_TOOL_ID);
	// user1Client.createJob(sessionId1, job);
	//
	// // wait until the job is scheduled
	// assertEquals(true, latch.await(1, TimeUnit.SECONDS));
	// }
}
