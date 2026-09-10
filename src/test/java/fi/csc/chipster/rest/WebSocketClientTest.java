package fi.csc.chipster.rest;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.rest.websocket.TopicConfig;
import fi.csc.chipster.rest.websocket.WebSocketClient;
import fi.csc.chipster.rest.websocket.WebSocketClosedException;
import fi.csc.chipster.rest.websocket.WebSocketErrorException;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.MessageHandler.Whole;

public class WebSocketClientTest {

	public static class TestReplyHandler implements Whole<String> {

		@Override
		public void onMessage(String message) {
			System.out.println("server received message: " + message);
		}

	}

	public static class TestTopicConfig implements TopicConfig {

		// set from the test thread, read on the server's threads
		private volatile boolean authorized = true;

		public void setAuthorized(boolean authorized) {
			this.authorized = authorized;
		}

		@Override
		public boolean isAuthorized(AuthPrincipal principal, String topicName) {
			return authorized;
		}

		@Override
		public String getMonitoringTag(String topicName) {
			return null;
		}

		@Override
		public List<String> getMonitoringTags() {
			return new ArrayList<>();
		}

		@Override
		public AuthPrincipal getUserPrincipal(String tokenKey) {
			return new AuthPrincipal("user", new HashSet<>());
		}

	}

	public static class TestMessageHandler implements Whole<String> {

		private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

		@Override
		public void onMessage(String message) {
			System.out.println("client received message: " + message);
			received.add(message);
		}

		public String poll(long timeoutSeconds) throws InterruptedException {
			return received.poll(timeoutSeconds, TimeUnit.SECONDS);
		}
	}

	public static final int PORT = freePort();
	public static final String uri = "ws://127.0.0.1:" + PORT;

	/*
	 * Not a fixed port. Anything else running this suite at the same time - a
	 * mutation-testing tool, a parallel build - would otherwise bind the same
	 * one, and a foreign server answering on it makes these tests pass or fail
	 * for reasons that have nothing to do with the client.
	 */
	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new IllegalStateException("no free port for the test server", e);
		}
	}

	private PubSubServer startServer(TopicConfig topicConfig, String name) throws ServletException {
		PubSubServer server = new PubSubServer(uri, new TestReplyHandler(), topicConfig, name);
		server.start();
		return server;
	}

	private WebSocketClient startClient(boolean retry, String name)
			throws InterruptedException, WebSocketErrorException, WebSocketClosedException {
		return startClient(retry, name, new TestMessageHandler());
	}

	private WebSocketClient startClient(boolean retry, String name, TestMessageHandler handler)
			throws InterruptedException, WebSocketErrorException, WebSocketClosedException {
		return new WebSocketClient(uri, handler, retry, name, new StaticCredentials("user", "password"));
	}

	@Test
	public void start() throws ServletException, DeploymentException, InterruptedException, WebSocketErrorException,
			WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = startServer(new TestTopicConfig(), "test-pub-sub-server");
		WebSocketClient client = null;
		try {
			client = startClient(false, "test-ws-client");
			client.ping();
		} finally {
			// all tests here share one port, and each client owns an HttpClient
			// thread pool, so anything left running would disturb the others
			if (client != null) {
				client.shutdown();
			}
			server.stop();
		}
	}

	@Test
	public void stop() throws ServletException, DeploymentException, InterruptedException, WebSocketErrorException,
			WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = startServer(new TestTopicConfig(), "test-pub-sub-server");
		WebSocketClient client = null;
		try {
			client = startClient(false, "test-ws-client");

			server.stop();

			try {
				client.ping();
				Assertions.fail("ping succeeded after the server was stopped");
			} catch (IOException e) {
			}
		} finally {
			if (client != null) {
				client.shutdown();
			}
			server.stop();
		}
	}

	@Test
	public void reconnect() throws ServletException, DeploymentException, InterruptedException, WebSocketErrorException,
			WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = startServer(new TestTopicConfig(), "test-pub-sub-server");
		WebSocketClient client = null;
		try {
			client = startClient(true, "test-ws-client");

			server.stop();
			// before restarting, so the client can't reconnect before we've
			// confirmed it noticed the server going away
			waitUntilDisconnected(client);
			server = startServer(new TestTopicConfig(), "test-pub-sub-server");
			waitUntilConnected(client, 10);

			client.ping();
		} finally {
			if (client != null) {
				client.shutdown();
			}
			server.stop();
		}
	}

	@Test
	public void receivesMessagesAlsoAfterReconnect()
			throws ServletException, DeploymentException, InterruptedException, WebSocketErrorException,
			WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = startServer(new TestTopicConfig(), "message-test-server");
		TestMessageHandler handler = new TestMessageHandler();
		WebSocketClient client = null;
		try {
			client = startClient(true, "message-test-client", handler);

			// publish() serializes to JSON, so a plain string arrives quoted
			server.publish("hello");
			Assertions.assertEquals("\"hello\"", handler.poll(10), "the client didn't get the message");

			// the message handler has to be registered again on the new
			// connection, or the client goes quiet without anything failing
			server.stop();
			waitUntilDisconnected(client);
			server = startServer(new TestTopicConfig(), "message-test-server");
			waitUntilConnected(client, 10);

			server.publish("hello again");
			Assertions.assertEquals("\"hello again\"", handler.poll(10),
					"the client didn't get the message after reconnecting");
		} finally {
			if (client != null) {
				client.shutdown();
			}
			server.stop();
		}
	}

	@Test
	public void shutdownStopsThreads() throws ServletException, DeploymentException, InterruptedException,
			WebSocketErrorException, WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = startServer(new TestTopicConfig(), "shutdown-threads-server");
		WebSocketClient client = null;
		try {
			// by name, not by count: these prefixes are shared with the server
			// and with the other tests' clients, so a count would blame this
			// client for threads it never started
			Set<String> before = connectionThreadNames();
			client = startClient(true, "shutdown-threads-client");
			// really a check on this test's own filter: a client with retries
			// on always has its uniquely named reconnect thread
			Assertions.assertFalse(added(before).isEmpty(), "the thread-name filter matched nothing");

			client.shutdown();
			client = null;

			/*
			 * The container owns the HttpClient, so stopping the container has
			 * to stop its pools too. They are not daemon threads: any left
			 * running would keep the JVM alive after a service tried to exit.
			 */
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
			while (!added(before).isEmpty() && System.nanoTime() < deadline) {
				Thread.sleep(50);
			}
			assertConnectionThreadsStopped(before);
		} finally {
			// only if an assertion above failed before the shutdown under test
			if (client != null) {
				client.shutdown();
			}
			server.stop();
		}
	}

	@Test
	public void shutdownWhileDisconnected() throws ServletException, DeploymentException, InterruptedException,
			WebSocketErrorException, WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = startServer(new TestTopicConfig(), "shutdown-down-server");
		WebSocketClient client = null;
		try {
			client = startClient(true, "shutdown-down-client");

			// the state a service is in when it exits during an update: its
			// server is already gone and the reconnect thread is between
			// tries. Just past the disconnect, so most of a 1 s backoff is
			// still ahead of the shutdown below
			server.stop();
			waitUntilDisconnected(client);
			Thread.sleep(1050);

			long start = System.nanoTime();
			client.shutdown();
			long millis = (System.nanoTime() - start) / 1_000_000;
			client = null;

			/*
			 * Measured at single-digit ms, but the window also covers stopping
			 * the container, which a loaded machine can stretch. A shutdown
			 * that had to wait out the delay would take the ~950 ms left of
			 * the backoff, so this sits well clear of both outcomes.
			 */
			Assertions.assertTrue(millis < 500,
					"shutdown() took " + millis + " ms with the client between retries");
		} finally {
			if (client != null) {
				client.shutdown();
			}
			server.stop();
		}
	}

	@Test
	public void reconnectsAfterLongerOutage() throws ServletException, DeploymentException, InterruptedException,
			WebSocketErrorException, WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = startServer(new TestTopicConfig(), "outage-test-server");
		WebSocketClient client = null;
		try {
			client = startClient(true, "outage-test-client");

			/*
			 * Down long enough to outlast the first attempt, which is the
			 * rolling-update case. A connect to a dead port fails without any
			 * endpoint callback, so nothing signals the reconnect loop - only
			 * its own repeated attempts bring the client back.
			 */
			server.stop();
			waitUntilDisconnected(client);
			Thread.sleep(5000);
			server = startServer(new TestTopicConfig(), "outage-test-server");

			waitUntilConnected(client, 10);
		} finally {
			if (client != null) {
				client.shutdown();
			}
			server.stop();
		}
	}

	@Test
	public void reconnectDoesNotLeakThreads()
			throws ServletException, DeploymentException, InterruptedException, WebSocketErrorException,
			WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = startServer(new TestTopicConfig(), "leak-test-server");
		WebSocketClient client = null;
		try {
			client = startClient(true, "leak-test-client");

			int before = countConnectionThreads();

			int reconnects = 5;
			for (int i = 0; i < reconnects; i++) {
				server.stop();
				waitUntilDisconnected(client);
				server = startServer(new TestTopicConfig(), "leak-test-server");
				waitUntilConnected(client, 10);
			}

			int growth = countConnectionThreads() - before;

			/*
			 * A single connection needs roughly a dozen threads (the HttpClient's own
			 * thread pool, its scheduler and the websocket executor). Reusing the
			 * HttpClient keeps that constant; building a new one per reconnect, as
			 * this client used to, grows it by about that much every time.
			 */
			Assertions.assertTrue(growth < 20, "connection thread count grew by " + growth + " over " + reconnects
					+ " reconnects - looks like each reconnect leaks its own HttpClient instead of reusing one");
		} finally {
			if (client != null) {
				client.shutdown();
			}
			server.stop();
		}
	}

	@Test
	public void unauthorizedClientFails() throws ServletException, DeploymentException, InterruptedException,
			WebSocketErrorException, IOException, TimeoutException {

		TestTopicConfig topicConfig = new TestTopicConfig();
		topicConfig.setAuthorized(false);
		PubSubServer server = startServer(topicConfig, "unauthorized-test-server");
		try {
			/*
			 * The server accepts the websocket upgrade and only then checks the
			 * authorization, so the constructor can only find out by pinging and
			 * being closed instead of ponged.
			 */
			WebSocketClosedException e = Assertions.assertThrows(WebSocketClosedException.class,
					() -> startClient(false, "unauthorized-test-client"));
			Assertions.assertEquals(CloseCodes.VIOLATED_POLICY, e.getCloseReason().getCloseCode());
		} finally {
			server.stop();
		}
	}

	@Test
	public void unauthorizedReconnectKeepsTrying()
			throws ServletException, DeploymentException, InterruptedException, WebSocketErrorException,
			WebSocketClosedException, IOException, TimeoutException {

		TestTopicConfig topicConfig = new TestTopicConfig();
		PubSubServer server = startServer(topicConfig, "give-up-test-server");
		WebSocketClient client = null;
		try {
			client = startClient(true, "give-up-test-client");

			// the token won't be accepted any more, e.g. because it expired
			topicConfig.setAuthorized(false);
			server.stop();
			server = startServer(topicConfig, "give-up-test-server");

			/*
			 * A server that refuses us is usually one that is restarting and
			 * can't validate our token yet, so the client has to keep trying
			 * rather than end its event stream for good.
			 *
			 * Proven by letting it in again afterwards: a client that gave up
			 * on the refusal never comes back, however long we wait.
			 */
			Thread.sleep(2000);
			Assertions.assertFalse(client.isConnected(), "the server accepted a client it should have refused");

			topicConfig.setAuthorized(true);
			/*
			 * What this pins down is narrow: the client doesn't shut itself
			 * down over a refusal, so it comes back once allowed in. It cannot
			 * show that the retry loop itself keeps trying refusals - every
			 * refusal releases a permit of its own, so the outer loop re-enters
			 * the retry either way.
			 */
			waitUntilConnected(client, 10);
		} finally {
			if (client != null) {
				client.shutdown();
			}
			server.stop();
		}
	}

	private void assertConnectionThreadsStopped(Set<String> before) {
		Set<String> left = added(before);

		// no tolerance for our own thread: exactly one per client, and it is
		// the one that parks forever if its loop stops noticing shutdown
		Assertions.assertTrue(left.stream().noneMatch(n -> n.startsWith("websocket-reconnect-")),
				"shutdown() left the client's own reconnect thread running: " + left);

		/*
		 * Jetty's pools get some: the pool gives up on its own threads after
		 * its stop timeout, so a loaded machine can leave a straggler. A
		 * skipped stop leaves a dozen, which this still catches.
		 */
		long pools = left.stream().filter(n -> !n.startsWith("websocket-reconnect-")).count();
		Assertions.assertTrue(pools <= 3, "shutdown() left " + pools + " connection threads running " + left
				+ "; they are not daemons, so a service would hang instead of exiting");
	}

	private void waitUntilConnected(WebSocketClient client, long timeoutSeconds) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
		while (!client.isConnected() && System.nanoTime() < deadline) {
			Thread.sleep(50);
		}
		Assertions.assertTrue(client.isConnected(),
				"the client didn't reconnect within " + timeoutSeconds + " s of the server accepting it again");
	}

	private void waitUntilDisconnected(WebSocketClient client) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (client.isConnected() && System.nanoTime() < deadline) {
			Thread.sleep(50);
		}
		Assertions.assertFalse(client.isConnected(), "the client didn't notice the server going away");
	}

	private int countConnectionThreads() {
		return connectionThreadNames().size();
	}

	private Set<String> connectionThreadNames() {
		return Thread.getAllStackTraces().keySet().stream().map(Thread::getName)
				// the client's own reconnect thread counts too: it is what
				// parks forever if its loop ever stops noticing shutdown
				.filter(n -> n.startsWith("HttpClient@") || n.startsWith("WebSocket@")
						|| n.startsWith("websocket-reconnect-"))
				.collect(Collectors.toCollection(HashSet::new));
	}

	// connection threads that exist now but didn't in the given snapshot
	private Set<String> added(Set<String> before) {
		Set<String> now = connectionThreadNames();
		now.removeAll(before);
		return now;
	}
}
