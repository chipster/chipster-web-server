package fi.csc.chipster.jobhistory;

import static org.junit.Assert.assertEquals;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import java.util.UUID;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.TestServerLauncher;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;

public class JobHistoryResourceTest {

	private static TestServerLauncher launcher;
	// Users with non admin right
	private static SessionDbClient sessionDBClient1;
	// Users with admin right
	private static JobHistoryClient jobHistoryClient2;

	private static UUID sessionID;
	private static int MAX_NUM = 100000;

	@BeforeClass
	public static void setUp() throws Exception {
		Config config = new Config();
		launcher = new TestServerLauncher(config);

		sessionDBClient1 = new SessionDbClient(launcher.getServiceLocator(), launcher.getUser1Token(), Role.CLIENT);

		jobHistoryClient2 = new JobHistoryClient(launcher.getServiceLocator(), launcher.getAdminToken());

		sessionID = sessionDBClient1.createSession(RestUtils.getRandomSession());
	}

	@AfterClass
	public static void tearDown() throws Exception {
		launcher.stop();
	}

	@Test
	public void get() throws RestException {
		assertEquals(200, jobHistoryClient2.get().getStatus());
	}

	/*
	 * @Test public void testSaveJob() throws InterruptedException,
	 * RestException { UUID jobId = sessionDBClient1.createJob(sessionID,
	 * RestUtils.getRandomJob()); Thread.sleep(200); assertEquals(true,
	 * jobHistoryClient2.getJobByID(jobId) != null); }
	 * 
	 * /*@Test public void testSaveJobDbPerformance() throws
	 * InterruptedException, RestException { for (int i = 0; i < 10; i++) {
	 * sessionDBClient1.createJob(sessionID, RestUtils.getRandomJob());
	 * Thread.sleep(100);
	 * 
	 * } assertEquals(true, jobHistoryClient2.getJobHistoryList().size() >
	 * 1000); }
	 */

	@Test
	public void inserTestJobs() throws InterruptedException, RestException {
		long startTime = System.nanoTime();

		for (int i = 0; i < MAX_NUM; i++) {
			JobHistoryModel j = new JobHistoryModel();

			Instant jobStartTime = getRandomTimeStamp();
			j.setStartTime(jobStartTime);
			j.setEndTime(jobStartTime.plus(30, ChronoUnit.SECONDS));
			j.setTimeDuration(Long.toString(Math.abs(Duration.between(j.getEndTime(), j.getStartTime()).getSeconds())));
			if (i % 5 == 0) {
				j.setJobStatus("NEW");
			} else if (i % 2 == 0) {
				j.setJobStatus("COMPLETED");
			} else {
				j.setJobStatus("ERROR");
			}

			j.setJobId(UUID.randomUUID());
			j.setUserName("testUser" + new Random().nextInt(50 - 1 + 1) + 1);
			j.setToolId("InputOutput.py");
			j.setToolName("Input Output");
			j.setOutput(getRandomString());
			jobHistoryClient2.saveTestJob(j);
			;

		}

		long endTime = System.nanoTime();
		System.out.println(endTime - startTime);
	}

	private Instant getRandomTimeStamp() {
		long offset = Timestamp.valueOf("2018-09-11 00:00:00").getTime();
		long end = Timestamp.valueOf("2018-09-14 00:00:00").getTime();
		long diff = end - offset + 1;
		Timestamp rand = new Timestamp(offset + (long) (Math.random() * diff));

		return rand.toInstant();

	}

	private String getRandomString() {
		Random rand = new Random();
		String possibleLetters = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ.";
		StringBuilder sb = new StringBuilder(1500);
		for (int j = 0; j < 10; j++) {
			for (int i = 0; i < 15; i++) {
				sb.append(possibleLetters.charAt(rand.nextInt(possibleLetters.length())));
				sb.append("\n ");
			}
		}

		return sb.toString();

	}

}
