// package fi.csc.chipster.scheduler;
//
// import static org.junit.jupiter.api.Assertions.assertEquals;
//
// import java.util.ArrayList;
// import java.util.HashSet;
// import java.util.UUID;
// import java.util.concurrent.CountDownLatch;
// import java.util.concurrent.TimeUnit;
//
// import org.apache.logging.log4j.LogManager;
// import org.apache.logging.log4j.Logger;
// import org.junit.jupiter.api.AfterAll;
// import org.junit.jupiter.api.BeforeAll;
// import org.junit.jupiter.api.Test;
//
// import fi.csc.chipster.auth.AuthenticationClient;
// import fi.csc.chipster.auth.model.Role;
// import fi.csc.chipster.rest.Config;
// import fi.csc.chipster.rest.RestUtils;
// import fi.csc.chipster.rest.TestServerLauncher;
// import fi.csc.chipster.rest.websocket.WebSocketClient;
// import fi.csc.chipster.rest.websocket.WebSocketClosedException;
// import fi.csc.chipster.scheduler.offer.JobCommand;
// import fi.csc.chipster.scheduler.offer.JobCommand.Command;
// import fi.csc.chipster.servicelocator.ServiceLocatorClient;
// import fi.csc.chipster.sessiondb.EventTest;
// import fi.csc.chipster.sessiondb.SessionDbClient;
// import fi.csc.chipster.sessiondb.model.Job;
// import jakarta.websocket.CloseReason.CloseCodes;
//
// public class OfferJobSchedulerTest {
//
// @SuppressWarnings("unused")
// private final Logger logger = LogManager.getLogger();
//
// private static TestServerLauncher launcher;
// private static String uri;
//
// private static String token;
// private static String userToken;
//
// private static SessionDbClient user1Client;
// private static UUID sessionId;
//
// @BeforeAll
// public static void setUp() throws Exception {
// Config config = new Config();
//
// launcher = new TestServerLauncher(config);
// user1Client = new SessionDbClient(launcher.getServiceLocator(),
// launcher.getUser1Token(), Role.CLIENT);
// sessionId = user1Client.createSession(RestUtils.getRandomSession());
//
// ServiceLocatorClient clientServiceLocator = launcher.getServiceLocator();
// ServiceLocatorClient adminServiceLocator =
// launcher.getServiceLocatorForAdmin();
// AuthenticationClient compAuthentictionClient = new
// AuthenticationClient(adminServiceLocator, Role.COMP, Role.COMP, Role.SERVER);
//
// token = compAuthentictionClient.getToken().toString();
// userToken = new AuthenticationClient(clientServiceLocator, "client",
// "clientPassword", Role.CLIENT).getToken().toString();
// // scheduler has only an internal address
// uri = adminServiceLocator.getInternalService(Role.SCHEDULER).getUri() +
// "/events";
// }
//
// @AfterAll
// public static void tearDown() throws Exception {
// launcher.stop();
// }
//
// @Test
// public void connect() throws Exception {
//
// ArrayList<String> messages = new ArrayList<>();
// CountDownLatch latch = new CountDownLatch(1);
//
// // wrong user
// try {
// EventTest.getTestClient(uri, null, messages, latch, false, userToken);
// assertEquals(true, false);
// } catch (WebSocketClosedException e) {
// assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
// }
//
// // unparseable token
// try {
// EventTest.getTestClient(uri, null, messages, latch, false,
// "unparseableToken");
// assertEquals(true, false);
// } catch (WebSocketClosedException e) {
// assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
// }
//
// // wrong token
// try {
// EventTest.getTestClient(uri, null, messages, latch, false,
// RestUtils.createId());
// assertEquals(true, false);
// } catch (WebSocketClosedException e) {
// assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
// }
//
// // no token
// try {
// EventTest.getTestClient(uri, null, messages, latch, false, null);
// assertEquals(true, false);
// } catch (WebSocketClosedException e) {
// assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
// }
// }
//
// @Test
// public void ping() throws Exception {
// ArrayList<String> messages = new ArrayList<>();
// CountDownLatch latch = new CountDownLatch(1);
//
// // correct settings
// WebSocketClient client = EventTest.getTestClient(uri, null, messages, latch,
// false, token);
// client.ping();
// client.shutdown();
// }
//
// /**
// * Test that the scheduler sends scheduler messages, when a new job is
// * created in the session-db
// * @throws Exception
// */
// @Test
// public void schedule() throws Exception {
//
// final ArrayList<String> messages = new ArrayList<>();
// final CountDownLatch latch = new CountDownLatch(1);
// WebSocketClient client = EventTest.getTestClient(uri, null, messages, latch,
// false, token);
//
// UUID jobId = user1Client.createJob(sessionId, RestUtils.getRandomJob());
//
// // wait for the message
// assertEquals(true, latch.await(1, TimeUnit.SECONDS));
// JobCommand cmd = RestUtils.parseJson(JobCommand.class, messages.get(0));
//
// assertEquals(Command.SCHEDULE, cmd.getCommand());
// assertEquals(sessionId, cmd.getSessionId());
// assertEquals(jobId, cmd.getJobId());
// assertEquals(null, cmd.getCompId());
//
// client.shutdown();
// }
//
//
// /**
// * Test the complete Job scheduling process
// * <li>post a job to session-db</li>
// * <li>wait for schedule command from scheduler</li>
// * <li>reply with offer</li>
// * <li>wait for choose command from scheduler</li>
// * @throws Exception
// */
// @Test
// public void choose() throws Exception {
//
// final ArrayList<String> messages = new ArrayList<>();
// final CountDownLatch latch = new CountDownLatch(1);
// WebSocketClient client = EventTest.getTestClient(uri, null, messages, latch,
// false, token);
//
// UUID jobId = user1Client.createJob(sessionId, RestUtils.getRandomJob());
//
// // wait for the schedule message
// assertEquals(true, latch.await(1, TimeUnit.SECONDS));
// JobCommand cmd = RestUtils.parseJson(JobCommand.class, messages.get(0));
//
// /*
// * TestClient can wait only once, but we can create a new one to wait
// * for the next message.
// */
// final ArrayList<String> messages2 = new ArrayList<>();
// final CountDownLatch latch2 = new CountDownLatch(1);
// WebSocketClient client2 = EventTest.getTestClient(uri, null, messages2,
// latch2, false, token);
//
// // imaginary comp responds with offer
// UUID compId = RestUtils.createUUID();
// client.sendText(RestUtils.asJson(new JobCommand(cmd.getSessionId(),
// cmd.getJobId(), compId, Command.OFFER)));
//
// // wait for the offer message
// assertEquals(true, latch2.await(1, TimeUnit.SECONDS));
// JobCommand cmd2 = RestUtils.parseJson(JobCommand.class, messages2.get(0));
//
// // scheduler responds with a choose message
// assertEquals(Command.CHOOSE, cmd2.getCommand());
// assertEquals(sessionId, cmd.getSessionId());
// assertEquals(jobId, cmd.getJobId());
// assertEquals(compId, cmd2.getCompId());
//
// client.shutdown();
// client2.shutdown();
// }
//
// @Test
// public void chooseFromMany() throws Exception {
//
// final ArrayList<String> messages = new ArrayList<>();
// final CountDownLatch latch = new CountDownLatch(1);
// WebSocketClient client = EventTest.getTestClient(uri, null, messages, latch,
// false, token);
//
// user1Client.createJob(sessionId, RestUtils.getRandomJob());
//
// // wait for the schedule message
// assertEquals(true, latch.await(1, TimeUnit.SECONDS));
// JobCommand cmd = RestUtils.parseJson(JobCommand.class, messages.get(0));
//
// /*
// * TestClient can wait only once, but we can create a new one to wait
// * for the next message.
// */
// final ArrayList<String> messages2 = new ArrayList<>();
// final CountDownLatch latch2 = new CountDownLatch(1);
// WebSocketClient client2 = EventTest.getTestClient(uri, null, messages2,
// latch2, false, token);
//
// // imaginary comps respond with offer
// HashSet<UUID> compIds = new HashSet<>();
// for (int i = 0; i < 10; i++) {
// UUID compId = RestUtils.createUUID();
// client.sendText(RestUtils.asJson(new JobCommand(cmd.getSessionId(),
// cmd.getJobId(), compId, Command.OFFER)));
// compIds.add(compId);
// }
// // wait for the offer message
// assertEquals(true, latch2.await(1, TimeUnit.SECONDS));
// JobCommand cmd2 = RestUtils.parseJson(JobCommand.class, messages2.get(0));
// assertEquals(true, compIds.contains(cmd2.getCompId()));
//
// // wait a little bit more to make sure that there is no more messages
// Thread.sleep(100);
// assertEquals(1, messages2.size());
//
// client.shutdown();
// client2.shutdown();
// }
//
// /**
// * When a client cancels a job, scheduler should inform comps
// *
// * @throws Exception
// */
// @Test
// public void cancel() throws Exception {
//
// Job job = RestUtils.getRandomJob();
// UUID jobId = user1Client.createJob(sessionId, job);
//
// // the job exists
// assertEquals(jobId, user1Client.getJob(sessionId, jobId).getJobId());
//
// // start listening for events only after scheduler has sent the SCHEDULE
// message
// Thread.sleep(100);
//
// final ArrayList<String> messages = new ArrayList<>();
// final CountDownLatch latch = new CountDownLatch(1);
// WebSocketClient client = EventTest.getTestClient(uri, null, messages, latch,
// false, token);
//
// // cancel it
// user1Client.deleteJob(sessionId, jobId);
//
// // scheduler should inform comps
// Thread.sleep(100);
//
// // wait for the message
// assertEquals(true, latch.await(1, TimeUnit.SECONDS));
// JobCommand cmd = RestUtils.parseJson(JobCommand.class, messages.get(0));
//
// assertEquals(Command.CANCEL, cmd.getCommand());
// assertEquals(sessionId, cmd.getSessionId());
// assertEquals(jobId, cmd.getJobId());
// assertEquals(null, cmd.getCompId());
//
// client.shutdown();
// }
//
// /**
// * Test that the scheduler tries to reschedule the job later when a comp is
// available
// * @throws Exception
// */
// @Test
// public void queue() throws Exception {
//
// final ArrayList<String> messages = new ArrayList<>();
// final CountDownLatch latch = new CountDownLatch(1);
// WebSocketClient client = EventTest.getTestClient(uri, null, messages, latch,
// false, token);
//
// // try to schedule all possible old jobs
// UUID compId = RestUtils.createUUID();
// client.sendText(RestUtils.asJson(new JobCommand(null, null, compId,
// Command.AVAILABLE)));
//
// // create a new job
// @SuppressWarnings("unused")
// UUID jobId = user1Client.createJob(sessionId, RestUtils.getRandomJob());
//
// // it is scheduled immediately
// assertEquals(true, latch.await(1, TimeUnit.SECONDS));
// JobCommand cmd = RestUtils.parseJson(JobCommand.class, messages.get(0));
//
// // tell scheduler that we can run this tool (which doesn't exist in reality)
// client.sendText(RestUtils.asJson(new JobCommand(sessionId, jobId, compId,
// Command.BUSY)));
//
// // without comp's response, the job should go back to the queue after some
// time
// Thread.sleep((new Config().getLong(Config.KEY_SCHEDULER_WAIT_TIMEOUT) + 2) *
// 1000);
//
// /*
// * TestClient can wait only once, but we can create a new one to wait
// * for the next message.
// */
// final ArrayList<String> messages2 = new ArrayList<>();
// final CountDownLatch latch2 = new CountDownLatch(1);
// WebSocketClient client2 = EventTest.getTestClient(uri, null, messages2,
// latch2, false, token);
//
// // when the comp has a free slot, it will send an AVAILABLE message
// client.sendText(RestUtils.asJson(new JobCommand(null, null, compId,
// Command.AVAILABLE)));
//
// // and the scheduler should try to reschedule the job
// assertEquals(true, latch2.await(1, TimeUnit.SECONDS));
// cmd = RestUtils.parseJson(JobCommand.class, messages2.get(0));
//
// // The first job may not be ours, the other tests have left other jobs
// waiting there.
// // Consider writing a better client for receiving multiple messages, so that
// we could
// // check that our job is one of them.
// assertEquals(Command.SCHEDULE, cmd.getCommand());
// //assertEquals(sessionId, cmd.getSessionId());
// //assertEquals(jobId, cmd.getJobId());
// assertEquals(null, cmd.getCompId());
//
// client.shutdown();
// client2.shutdown();
// }
// }
