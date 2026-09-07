package fi.csc.chipster.rest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

		@Override
		public boolean isAuthorized(AuthPrincipal principal, String topicName) {
			return true;
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

		@Override
		public void onMessage(String message) {
			System.out.println("client received message: " + message);
		}
	}

	public static final int PORT = 8200;
	public static final String uri = "ws://127.0.0.1:" + PORT;

	@Test
	public void start() throws ServletException, DeploymentException, InterruptedException, WebSocketErrorException,
			WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = new PubSubServer(uri, new TestReplyHandler(), new TestTopicConfig(),
				"test-pub-sub-server");
		server.start();
		WebSocketClient client = new WebSocketClient(uri, new jakarta.websocket.MessageHandler.Whole<String>() {

			@Override
			public void onMessage(String message) {
				System.out.println("client received message: " + message);
			}
		}, false, "test-ws-client", new StaticCredentials("user", "password"));

		client.ping();
		server.stop();
	}

	@Test
	public void stop() throws ServletException, DeploymentException, InterruptedException, WebSocketErrorException,
			WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = new PubSubServer(uri, new TestReplyHandler(), new TestTopicConfig(),
				"test-pub-sub-server");
		server.start();
		WebSocketClient client = new WebSocketClient(uri, new jakarta.websocket.MessageHandler.Whole<String>() {

			@Override
			public void onMessage(String message) {
				System.out.println("client received message: " + message);
			}
		}, false, "test-ws-client", new StaticCredentials("user", "password"));

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
	}

	@Test
	public void reconnect() throws ServletException, DeploymentException, InterruptedException, WebSocketErrorException,
			WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = new PubSubServer(uri, new TestReplyHandler(), new TestTopicConfig(),
				"test-pub-sub-server");
		server.start();
		WebSocketClient client = new WebSocketClient(uri, new jakarta.websocket.MessageHandler.Whole<String>() {

			@Override
			public void onMessage(String message) {
				System.out.println("client received message: " + message);
			}
		}, true, "test-ws-client", new StaticCredentials("user", "password"));

		server.stop();
		server = new PubSubServer(uri, new TestReplyHandler(), new TestTopicConfig(), "test-pub-sub-server");
		server.start();

		// it takes a while for the client to notice the disconnection
		Thread.sleep(2000);
		// wait for the reconnection
		client.waitForConnection();

		client.ping();
		// client must be shutdown when the retry is enabled
		client.shutdown();
		server.stop();

		Thread.sleep(10);
	}

	@Test
	public void reconnectDoesNotLeakThreads() throws ServletException, DeploymentException, InterruptedException,
			WebSocketErrorException, WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = new PubSubServer(uri, new TestReplyHandler(), new TestTopicConfig(),
				"leak-test-server");
		server.start();

		WebSocketClient client = new WebSocketClient(uri, new TestMessageHandler(), true, "leak-test-client",
				new StaticCredentials("user", "password"));

		int before = countHttpClientThreads();

		int reconnects = 5;
		for (int i = 0; i < reconnects; i++) {
			server.stop();
			server = new PubSubServer(uri, new TestReplyHandler(), new TestTopicConfig(), "leak-test-server");
			server.start();

			// it takes a while for the client to notice the disconnection
			Thread.sleep(2000);
			client.waitForConnection();
		}

		int after = countHttpClientThreads();

		client.shutdown();
		server.stop();

		/*
		 * A single connection needs roughly a dozen threads (HttpClient's own
		 * thread pool, its scheduler and the websocket executor). If each
		 * reconnect built its own HttpClient instead of reusing one, this
		 * would grow by roughly that much on every single reconnect.
		 */
		int allowedGrowth = 20;
		int actualGrowth = after - before;
		Assertions.assertTrue(actualGrowth < allowedGrowth,
				"HttpClient/WebSocket thread count grew by " + actualGrowth + " after " + reconnects
						+ " reconnects (before=" + before + ", after=" + after
						+ ") - looks like each reconnect is leaking its own HttpClient instead of reusing one");
	}

	@Test
	public void shutdownDuringReconnectIsPrompt() throws ServletException, DeploymentException, InterruptedException,
			WebSocketErrorException, WebSocketClosedException, IOException, TimeoutException {

		PubSubServer server = new PubSubServer(uri, new TestReplyHandler(), new TestTopicConfig(),
				"shutdown-test-server");
		server.start();

		WebSocketClient client = new WebSocketClient(uri, new TestMessageHandler(), true, "shutdown-test-client",
				new StaticCredentials("user", "password"));

		// stop the server for good, so the client ends up in its reconnect
		// loop (1 s retries at this point) with nothing to connect to
		server.stop();

		// don't just sleep and hope: prove the client has seen the disconnect,
		// otherwise shutdown() would run against a still-connected client and
		// the timing assertion below would pass without testing anything.
		// Sending fails with an IOException as soon as the session is closed,
		// and keeps failing ("not connected") once the retry loop has replaced
		// the endpoint
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		boolean disconnected = false;
		while (System.nanoTime() < deadline) {
			try {
				client.sendText("still there?");
				Thread.sleep(50);
			} catch (IOException e) {
				disconnected = true;
				break;
			}
		}
		Assertions.assertTrue(disconnected, "client didn't notice the server going away");

		// let the retry loop get from the onClose() callback into its first
		// retry sleep
		Thread.sleep(500);

		/*
		 * shutdown() has to stop the shared HttpClient, and Jetty waits up to its
		 * 5 s stop timeout for that pool's threads to finish. The reconnect loop
		 * runs on one of those threads and needs stateLock to exit, so if
		 * shutdown() stopped the HttpClient while still holding stateLock, this
		 * would take the full 5 s and Jetty would log a "Couldn't stop Thread"
		 * warning. Stopping it outside the lock lets the loop notice the
		 * shutdown and exit as soon as its current 1 s sleep ends.
		 */
		long start = System.nanoTime();
		client.shutdown();
		long millis = (System.nanoTime() - start) / 1_000_000;

		Assertions.assertTrue(millis < 3000, "shutdown() during a reconnect loop took " + millis
				+ " ms - looks like it stops the HttpClient while holding stateLock");
	}

	private int countHttpClientThreads() {
		return (int) Thread.getAllStackTraces().keySet().stream()
				.filter(t -> t.getName().startsWith("HttpClient@") || t.getName().startsWith("WebSocket@"))
				.count();
	}
}
