package fi.csc.chipster.rest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
}
