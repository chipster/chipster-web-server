package fi.csc.chipster.rest;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import fi.csc.chipster.rest.websocket.Subscriber;
import fi.csc.chipster.rest.websocket.Topic;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Extension;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

public class SubscriberTest {

	private static class StubBasic implements RemoteEndpoint.Basic {
		@Override
		public void sendText(String text) throws IOException {
		}

		@Override
		public void sendBinary(ByteBuffer data) throws IOException {
		}

		@Override
		public void sendText(String partialMessage, boolean isLast) throws IOException {
		}

		@Override
		public void sendBinary(ByteBuffer partialByte, boolean isLast) throws IOException {
		}

		@Override
		public OutputStream getSendStream() throws IOException {
			return null;
		}

		@Override
		public Writer getSendWriter() throws IOException {
			return null;
		}

		@Override
		public void sendObject(Object data) throws IOException, EncodeException {
		}

		@Override
		public void setBatchingAllowed(boolean allowed) throws IOException {
		}

		@Override
		public boolean getBatchingAllowed() {
			return false;
		}

		@Override
		public void flushBatch() throws IOException {
		}

		@Override
		public void sendPing(ByteBuffer applicationData) throws IOException {
		}

		@Override
		public void sendPong(ByteBuffer applicationData) throws IOException {
		}
	}

	private static class StubSession implements Session {
		private final RemoteEndpoint.Basic basic;

		StubSession(RemoteEndpoint.Basic basic) {
			this.basic = basic;
		}

		@Override
		public RemoteEndpoint.Basic getBasicRemote() {
			return basic;
		}

		@Override
		public void close() throws IOException {
		}

		@Override
		public void close(CloseReason closeReason) throws IOException {
		}

		@Override
		public RemoteEndpoint.Async getAsyncRemote() {
			return null;
		}

		@Override
		public String getId() {
			return "test";
		}

		@Override
		public void addMessageHandler(MessageHandler handler) throws IllegalStateException {
		}

		@Override
		public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Whole<T> handler) {
		}

		@Override
		public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Partial<T> handler) {
		}

		@Override
		public Set<MessageHandler> getMessageHandlers() {
			return Collections.emptySet();
		}

		@Override
		public void removeMessageHandler(MessageHandler handler) {
		}

		@Override
		public String getProtocolVersion() {
			return "13";
		}

		@Override
		public String getNegotiatedSubprotocol() {
			return "";
		}

		@Override
		public List<Extension> getNegotiatedExtensions() {
			return Collections.emptyList();
		}

		@Override
		public boolean isSecure() {
			return false;
		}

		@Override
		public boolean isOpen() {
			return true;
		}

		@Override
		public URI getRequestURI() {
			return null;
		}

		@Override
		public Map<String, List<String>> getRequestParameterMap() {
			return Collections.emptyMap();
		}

		@Override
		public String getQueryString() {
			return "";
		}

		@Override
		public Map<String, String> getPathParameters() {
			return Collections.emptyMap();
		}

		@Override
		public Map<String, Object> getUserProperties() {
			return new HashMap<>();
		}

		@Override
		public Principal getUserPrincipal() {
			return null;
		}

		@Override
		public Set<Session> getOpenSessions() {
			return Collections.emptySet();
		}

		@Override
		public WebSocketContainer getContainer() {
			return null;
		}

		@Override
		public void setMaxIdleTimeout(long milliseconds) {
		}

		@Override
		public long getMaxIdleTimeout() {
			return 0;
		}

		@Override
		public void setMaxTextMessageBufferSize(int length) {
		}

		@Override
		public int getMaxTextMessageBufferSize() {
			return 0;
		}

		@Override
		public void setMaxBinaryMessageBufferSize(int length) {
		}

		@Override
		public int getMaxBinaryMessageBufferSize() {
			return 0;
		}
	}

	@Test
	public void queueOverflowClosesConnection() throws Exception {
		CountDownLatch sendStarted = new CountDownLatch(1);
		CountDownLatch blockSend = new CountDownLatch(1);
		AtomicReference<CloseReason> closedWith = new AtomicReference<>();

		StubBasic basic = new StubBasic() {
			@Override
			public void sendText(String text) throws IOException {
				sendStarted.countDown();
				try {
					blockSend.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("interrupted");
				}
			}
		};
		StubSession session = new StubSession(basic) {
			@Override
			public void close(CloseReason reason) {
				// note: does not declare throws IOException, so the IOException-from-close
				// path in Subscriber.enqueue() is not exercised by this test
				closedWith.set(reason);
			}
		};

		Subscriber subscriber = Subscriber.create(session, "user", 1);
		try {
			subscriber.enqueue("msg1");
			Assertions.assertTrue(sendStarted.await(5, TimeUnit.SECONDS), "virtual thread should start sending");

			// virtual thread is now blocked in sendText; queue is empty
			subscriber.enqueue("msg2"); // fills the 1-slot queue
			boolean result = subscriber.enqueue("msg3"); // queue full, triggers close

			Assertions.assertFalse(result, "queue-full enqueue should return false");
			Assertions.assertNotNull(closedWith.get(), "session.close() should have been called");
			Assertions.assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, closedWith.get().getCloseCode());
			Assertions.assertTrue(subscriber.isQueueFullDisconnect(), "queueFullDisconnect flag should be set");
		} finally {
			blockSend.countDown();
			subscriber.stop();
		}
	}

	@Test
	public void slowSubscriberDoesNotBlockFast() throws Exception {
		CountDownLatch sendStarted = new CountDownLatch(1);
		CountDownLatch blockSend = new CountDownLatch(1);

		StubBasic slowBasic = new StubBasic() {
			@Override
			public void sendText(String text) throws IOException {
				sendStarted.countDown();
				try {
					blockSend.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("interrupted");
				}
			}
		};

		CountDownLatch fastReceived = new CountDownLatch(10);
		StubBasic fastBasic = new StubBasic() {
			@Override
			public void sendText(String text) throws IOException {
				fastReceived.countDown();
			}
		};

		Topic topic = new Topic();
		Subscriber slowSub = Subscriber.create(new StubSession(slowBasic), "user", 100);
		Subscriber fastSub = Subscriber.create(new StubSession(fastBasic), "user", 100);
		topic.add(slowSub);
		topic.add(fastSub);

		for (int i = 0; i < 10; i++) {
			topic.publish("msg-" + i);
		}
		Assertions.assertTrue(sendStarted.await(5, TimeUnit.SECONDS), "slow subscriber should start blocking");
		Assertions.assertTrue(fastReceived.await(5, TimeUnit.SECONDS),
				"fast subscriber should receive all messages while slow one is blocked");

		blockSend.countDown();
		slowSub.stop();
		fastSub.stop();
	}
}
