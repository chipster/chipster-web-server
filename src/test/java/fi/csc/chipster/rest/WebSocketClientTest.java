package fi.csc.chipster.rest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

	public static final int PORT = 8200;
	public static final String uri = "ws://127.0.0.1:" + PORT;

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
				try {
					client.ping();
				} catch (TimeoutException te) {
					// try again
					client.ping();
				}
				Assertions.fail();
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
			server = startServer(new TestTopicConfig(), "test-pub-sub-server");

			waitUntilDisconnected(client);
			client.waitForConnection();

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
			server = startServer(new TestTopicConfig(), "message-test-server");
			waitUntilDisconnected(client);
			client.waitForConnection();

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
				server = startServer(new TestTopicConfig(), "leak-test-server");

				waitUntilDisconnected(client);
				client.waitForConnection();
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
	public void unauthorizedReconnectGivesUp()
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

			// retrying a connection the server refuses on principle would go on
			// for the life of the process, so the client has to stop by itself
			Assertions.assertTrue(waitUntilReconnectThreadStops(client),
					"the client kept retrying a connection closed as VIOLATED_POLICY");
			Assertions.assertFalse(client.isConnected());
		} finally {
			if (client != null) {
				client.shutdown();
			}
			server.stop();
		}
	}

	private void waitUntilDisconnected(WebSocketClient client) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (client.isConnected() && System.nanoTime() < deadline) {
			Thread.sleep(50);
		}
		Assertions.assertFalse(client.isConnected(), "the client didn't notice the server going away");
	}

	private boolean waitUntilReconnectThreadStops(WebSocketClient client) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (System.nanoTime() < deadline) {
			if (Thread.getAllStackTraces().keySet().stream()
					.noneMatch(t -> t.getName().startsWith("websocket-reconnect-give-up-test-client"))) {
				return true;
			}
			Thread.sleep(50);
		}
		return false;
	}

	private int countConnectionThreads() {
		return (int) Thread.getAllStackTraces().keySet().stream()
				.filter(t -> t.getName().startsWith("HttpClient@") || t.getName().startsWith("WebSocket@"))
				.count();
	}
}
