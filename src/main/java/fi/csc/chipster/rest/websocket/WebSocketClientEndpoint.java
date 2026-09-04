package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;

public class WebSocketClientEndpoint extends Endpoint {

	public static interface EndpointListener {
		// `source` identifies which connect attempt's endpoint this callback
		// belongs to. Unlike `session`, it's never null (even for a
		// pre-Session upgrade failure), so it's the reliable way to tell a
		// callback for a superseded attempt apart from one for the current
		// connection.
		public void onOpen(WebSocketClientEndpoint source, Session session, EndpointConfig config);

		public void onClose(WebSocketClientEndpoint source, Session session, CloseReason reason);

		public void onError(WebSocketClientEndpoint source, Session session, Throwable thr);
	}

	private static final Logger logger = LogManager.getLogger();

	private MessageHandler messageHandler;
	// initialized eagerly (not just in onOpen()) so waitForDisconnect() can't
	// NPE if called before the handshake completes
	private CountDownLatch disconnectLatch = new CountDownLatch(1);
	private CountDownLatch connectLatch = new CountDownLatch(1);
	private CloseReason closeReason;
	private Throwable throwable;
	// written in onOpen() on a container thread, read from close()/sendText()/
	// ping() on other threads
	private volatile Session session;
	private EndpointListener endpointListener;

	public WebSocketClientEndpoint(MessageHandler.Whole<String> messageHandler, EndpointListener endpointListener) {
		this.messageHandler = messageHandler;
		this.endpointListener = endpointListener;
	}

	@Override
	public void onOpen(Session session, EndpointConfig config) {

		logger.debug("WebSocket client onOpen");

		this.session = session;

		if (messageHandler != null) {
			session.addMessageHandler(messageHandler);
		}

		/*
		 * Wait for connection or error
		 * 
		 * If the server would use HTTP errors for signaling e.g. authentication errors,
		 * at this point
		 * we would already know that the connection was successful. Unfortunately JSR
		 * 356 Java API
		 * for WebSocket doesn't support servlet filters or other methods
		 * for responding with HTTP errors to the original WebSocket upgrade request.
		 * 
		 * The server will check the authentication in the onOpen() method and close the
		 * connection if
		 * the authentication fails. We'll know that the authentication failed when the
		 * onClose() method
		 * is called here in the client. The problem is to know when the authentication
		 * was
		 * accepted. This is solved by sending a ping. If we get the ping reply, we know
		 * that authentication
		 * was accepted.
		 * 
		 * We have to wait for the ping reply in another thread, blocking this onOpen()
		 * method seems to stop also
		 * the ping or it's reply (understandably).
		 * 
		 * Side note: this logic is not critical for the information security, that has
		 * to be taken care in the server
		 * side. However, this is critical for reliable tests and for the server's to
		 * notice when their connection
		 * to other services fail.
		 */
		new Thread(new Runnable() {

			@Override
			public void run() {
				try {
					ping();
					connectLatch.countDown();

					endpointListener.onOpen(WebSocketClientEndpoint.this, session, config);

				} catch (IllegalArgumentException | IOException | TimeoutException | InterruptedException e) {
					logger.warn("WebSocket client error", e);
					// the ping failed, so connectLatch was never counted down
					// above: without this, waitForConnection() would block
					// forever instead of surfacing this as a connect failure
					throwable = e;
					connectLatch.countDown();
					disconnectLatch.countDown();
				}
			}

		}, "websocket-connection-ping").start();
	}

	@Override
	public void onClose(Session session, CloseReason reason) {

		logger.debug("WebSocket client onClose: " + reason);

		closeReason = reason;
		connectLatch.countDown();
		disconnectLatch.countDown();

		this.endpointListener.onClose(this, session, reason);
	}

	@Override
	public void onError(Session session, Throwable thr) {

		logger.debug("WebSocket client onError: " + thr.getMessage());

		throwable = thr;
		connectLatch.countDown();
		disconnectLatch.countDown();

		this.endpointListener.onError(this, session, thr);
	}

	// returns false if there was no open session to close, e.g. because the
	// handshake for this endpoint is still in progress; the caller shouldn't
	// then expect a disconnect to wait for
	public boolean close() throws IOException {
		if (session == null) {
			return false;
		}
		session.close(new CloseReason(CloseCodes.NORMAL_CLOSURE, "client closing"));
		return true;
	}

	public boolean waitForDisconnect(long timeout) throws InterruptedException {
		logger.debug("WebSocket client will wait for disconnect max " + timeout + " seconds");
		return disconnectLatch.await(timeout, TimeUnit.SECONDS);
	}

	public void waitForConnection() throws InterruptedException, WebSocketClosedException, WebSocketErrorException {
		logger.debug("WebSocket client waiting for connection " + closeReason);
		connectLatch.await();

		if (closeReason != null) {
			throw new WebSocketClosedException(closeReason);

		} else if (throwable != null) {

			// most likely error in the HTTP upgrade request, probably happens
			// only if the configuration or network is broken
			throw new WebSocketErrorException(throwable);
		}
	}

	public void sendText(String text) throws IOException {
		// session is only set once onOpen() has run; still null if this
		// endpoint's handshake is still in progress
		if (session == null) {
			throw new IOException("not connected");
		}
		session.getBasicRemote().sendText(text);
	}

	public void ping() throws IllegalArgumentException, IOException, TimeoutException, InterruptedException {
		logger.debug("WebSocket client sends ping");
		if (session == null) {
			throw new IOException("not connected");
		}
		PongHandler pongHandler = new PongHandler();
		session.addMessageHandler(pongHandler);
		session.getBasicRemote().sendPing(null);
		pongHandler.await();
		session.removeMessageHandler(pongHandler);
	}

	public static class PongHandler implements MessageHandler.Whole<PongMessage> {
		private CountDownLatch latch = new CountDownLatch(1);

		@Override
		public void onMessage(PongMessage message) {
			logger.debug("WebSocket client received pong");
			latch.countDown();
		}

		public void await() throws TimeoutException, InterruptedException {
			int timeout = 2;
			logger.debug("WebSocket client will wait for pong max " + timeout + " seconds");
			boolean received = latch.await(timeout, TimeUnit.SECONDS);
			if (!received) {
				throw new TimeoutException("timeout while waiting for pong message");
			}
		}
	}
}
