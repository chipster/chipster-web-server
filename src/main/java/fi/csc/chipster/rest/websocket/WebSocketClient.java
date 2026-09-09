package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee10.websocket.jakarta.client.JakartaWebSocketClientContainerProvider;

import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.websocket.WebSocketClientEndpoint.EndpointListener;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.MessageHandler.Whole;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.ws.rs.core.UriBuilder;

/**
 * WebSocket client that reconnects when the connection is lost
 *
 * One thread does all the connecting: the caller's thread in the constructor,
 * and after that the client's own reconnect thread. Jetty's callbacks
 * (onClose(), onError()) only signal that thread and return immediately.
 *
 * That is what keeps this class simple: reconnecting can't race another
 * reconnect, can't be started twice for one disconnect, and can't occupy a
 * container thread that Jetty still needs. The signal is only a hint to go and
 * look - whether we are connected is decided by asking the session, never by
 * counting callbacks - so a duplicate or late signal is harmless.
 */
public class WebSocketClient implements EndpointListener {

	public static final Logger logger = LogManager.getLogger();

	private static final int PONG_TIMEOUT_S = 2;
	private static final int SHUTDOWN_TIMEOUT_S = 5;
	private static final int CONNECT_WAIT_TIMEOUT_S = 30;

	private final String name;
	private final String uri;
	private final Whole<String> messageHandler;
	private final CredentialsProvider credentials;
	// null when this client shouldn't reconnect
	private final RetryHandler retryHandler;

	/*
	 * Written by the connecting thread (the constructor's caller, and after
	 * that the reconnect thread) and read from anywhere, hence volatile. The
	 * reconnect thread has exited by the time shutdown() closes them, unless
	 * it had to be abandoned - see shutdown().
	 */
	private volatile HttpClient httpClient;
	private volatile WebSocketContainer container;
	private volatile Session session;
	private volatile WebSocketClientEndpoint endpoint;

	// a permit means "the connection may be gone, go and check"
	private final Semaphore disconnected = new Semaphore(0);
	private volatile boolean closed = false;
	private Thread reconnectThread;

	public WebSocketClient(final String uri, final Whole<String> messageHandler, boolean retry, final String name,
			CredentialsProvider credentials)
			throws InterruptedException, WebSocketErrorException, WebSocketClosedException {

		this.name = name;
		this.uri = uri;
		this.messageHandler = messageHandler;
		this.credentials = credentials;

		/*
		 * Handle retries in this class instead of letting Tyrus to do it
		 *
		 * Tyrus would try to reconnect always to the same URL, which won't work after
		 * the token has expired.
		 */
		this.retryHandler = retry ? new RetryHandler() : null;

		try {
			connect();
		} catch (WebSocketErrorException | WebSocketClosedException | InterruptedException e) {
			// nobody will call shutdown() on an object whose constructor threw
			closed = true;
			closeResources();
			throw e;
		}

		if (retryHandler != null) {
			reconnectThread = new Thread(this::reconnectLoop, "websocket-reconnect-" + name);
			reconnectThread.setDaemon(true);
			reconnectThread.start();
		}
	}

	/**
	 * Connect and verify that the server accepted us
	 *
	 * Called by the constructor and after that only by the reconnect thread, so
	 * it never runs twice at the same time.
	 */
	private void connect() throws WebSocketErrorException, InterruptedException, WebSocketClosedException {

		// the previous attempt's container, if any. The HttpClient below is
		// deliberately kept running and reused: building a new one for every
		// reconnect is what used to leak its whole thread pool every time
		stopContainer();

		if (httpClient == null) {
			try {
				httpClient = new HttpClient();
				httpClient.start();
			} catch (Exception e) {
				// it may have started some threads before failing
				stopHttpClient();
				throw new WebSocketErrorException(e);
			}
		}

		try {
			container = JakartaWebSocketClientContainerProvider.getContainer(httpClient);
		} catch (Exception e) {
			throw new WebSocketErrorException(e);
		}

		/*
		 * Disable idle timeout in the client
		 *
		 * Let's try this first. Clearing non-cleanly closed connections is more
		 * important for the server
		 * to avoid resource leaks. If the OS doesn't close stale connections reliably,
		 * then we'll have to implement
		 * some kind of ping timer to keep the connection open.
		 */
		container.setDefaultMaxSessionIdleTimeout(-1);

		endpoint = new WebSocketClientEndpoint(messageHandler, this);

		UriBuilder uriBuilder = UriBuilder.fromUri(this.uri);
		if (credentials != null) {
			uriBuilder = uriBuilder.queryParam("token", credentials.getPassword().toString());
		}

		logger.info("websocket client " + name + " connecting to " + uri);

		try {
			session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(),
					new URI(uriBuilder.toString()));
		} catch (Exception e) {
			throw new WebSocketErrorException(e);
		}

		verifyConnection();

		logger.info("websocket client " + name + " connected succesfully: " + uri);
		if (retryHandler != null) {
			retryHandler.reset();
		}
	}

	/*
	 * Check that the server really accepted the connection, by pinging it
	 *
	 * If the server would use HTTP errors for signaling e.g. authentication errors,
	 * at this point we would already know that the connection was successful.
	 * Unfortunately JSR 356 Java API for WebSocket doesn't support servlet filters
	 * or other methods for responding with HTTP errors to the original WebSocket
	 * upgrade request.
	 *
	 * The server checks the authentication in its onOpen() and closes the
	 * connection if it fails, so a pong is the only positive proof that we were let
	 * in. This runs on the connecting thread, not in the endpoint's onOpen():
	 * blocking there would stop the pong from arriving in the first place.
	 *
	 * Side note: this logic is not critical for the information security, that has
	 * to be taken care in the server side. However, this is critical for reliable
	 * tests and for the server's to notice when their connection to other services
	 * fail.
	 */
	private void verifyConnection() throws WebSocketErrorException, WebSocketClosedException, InterruptedException {
		try {
			ping();
		} catch (IOException | TimeoutException e) {
			// a server that rejected us has closed the connection, and the close
			// reason says why - report that instead of the ping failure it caused
			CloseReason reason = endpoint.getCloseReason();
			if (reason != null) {
				throw new WebSocketClosedException(reason);
			}
			throw new WebSocketErrorException(e);
		}
	}

	private void reconnectLoop() {
		while (!closed) {
			try {
				disconnected.acquire();
			} catch (InterruptedException e) {
				// shutdown() interrupts us; the loop condition decides
				continue;
			}
			// onClose() and onError() both signal the same disconnect, and so
			// does every failed attempt below - one round of reconnecting
			// covers them all, and isConnected() below rejects false alarms
			disconnected.drainPermits();

			if (!closed && !isConnected()) {
				reconnect();
			}
		}
		logger.debug("websocket client " + name + " reconnect thread finished");
	}

	/**
	 * Retry until connected, until the server tells us not to, or until
	 * shutdown(). Runs only on the reconnect thread.
	 */
	private void reconnect() {
		while (!closed) {

			CloseReason reason = endpoint.getCloseReason();
			if (isUnrecoverable(reason)) {
				logger.error("websocket client " + name + " was closed by the server as " + reason.getCloseCode()
						+ " (" + reason.getReasonPhrase() + "), not reconnecting");
				// nothing will reconnect this client any more, so release its
				// resources here instead of leaking them for the life of the
				// process. A later shutdown() is then a no-op
				closed = true;
				closeResources();
				return;
			}

			long delay = retryHandler.nextDelaySeconds();
			logger.info("websocket client " + name + " reconnecting in " + delay + " s");
			if (!sleep(delay)) {
				return;
			}

			try {
				connect();
				return;
			} catch (WebSocketErrorException | WebSocketClosedException | InterruptedException e) {
				logger.warn("websocket client " + name + " reconnection failed: " + e.getMessage());
			}
		}
	}

	// false if shutdown() woke us up, meaning the caller should give up
	private boolean sleep(long seconds) {
		try {
			Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
		} catch (InterruptedException e) {
			// shutdown() interrupts to cut the wait short
		}
		return !closed;
	}

	// a server that closes for a reason like this won't accept us however many
	// times we try (an expired token, a topic we aren't allowed to subscribe)
	private boolean isUnrecoverable(CloseReason reason) {
		return reason != null && CloseCodes.VIOLATED_POLICY == reason.getCloseCode();
	}

	public boolean isConnected() {
		Session current = session;
		return current != null && current.isOpen();
	}

	public void sendText(String text) throws InterruptedException, IOException {
		Session current = session;
		if (current == null || !current.isOpen()) {
			throw new IOException("not connected");
		}
		current.getBasicRemote().sendText(text);
	}

	public void ping() throws IOException, TimeoutException, InterruptedException {
		Session current = session;
		if (current == null || !current.isOpen()) {
			throw new IOException("not connected");
		}

		logger.debug("websocket client " + name + " sends ping");
		CountDownLatch pong = new CountDownLatch(1);
		MessageHandler.Whole<PongMessage> pongHandler = message -> {
			logger.debug("websocket client " + name + " received pong");
			pong.countDown();
		};

		current.addMessageHandler(PongMessage.class, pongHandler);
		try {
			current.getBasicRemote().sendPing(null);
			if (!pong.await(PONG_TIMEOUT_S, TimeUnit.SECONDS)) {
				throw new TimeoutException("timeout while waiting for pong message");
			}
		} finally {
			try {
				current.removeMessageHandler(pongHandler);
			} catch (Exception e) {
				// the session closed under us; it's being torn down anyway
				logger.debug("websocket client " + name + " failed to remove the pong handler", e);
			}
		}
	}

	/*
	 * For reconnection tests
	 */
	public void waitForConnection() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONNECT_WAIT_TIMEOUT_S);
		while (!isConnected() && !closed && System.nanoTime() < deadline) {
			Thread.sleep(50);
		}
		if (!isConnected()) {
			throw new IllegalStateException(
					"websocket client " + name + " didn't connect in " + CONNECT_WAIT_TIMEOUT_S + " s");
		}
	}

	public void shutdown() throws IOException {
		logger.debug("shutdown websocket client " + name);

		closed = true;

		if (reconnectThread != null) {
			// wake it from waiting for a signal, or from a retry delay
			disconnected.release();
			reconnectThread.interrupt();
			try {
				reconnectThread.join(TimeUnit.SECONDS.toMillis(SHUTDOWN_TIMEOUT_S));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (reconnectThread.isAlive()) {
				// most likely stuck in a connect attempt against an
				// unresponsive server; closing the resources below fails that
				// attempt, and the thread then exits on its own
				logger.warn("websocket client " + name
						+ " reconnect thread didn't stop in time, closing its resources anyway");
			}
		}

		closeResources();
	}

	// safe to call more than once: every field is null afterwards
	private void closeResources() {
		closeSession();
		stopContainer();
		stopHttpClient();
	}

	private void closeSession() {
		Session current = session;
		session = null;
		if (current == null) {
			return;
		}
		try {
			if (current.isOpen()) {
				current.close(new CloseReason(CloseCodes.NORMAL_CLOSURE, "client closing"));
			}
		} catch (IOException e) {
			logger.warn("failed to close the session of " + name, e);
		}
	}

	private void stopContainer() {
		WebSocketContainer current = container;
		container = null;
		if (current == null) {
			return;
		}
		try {
			JakartaWebSocketClientContainerProvider.stop(current);
		} catch (Exception e) {
			logger.warn("failed to stop the websocket container of " + name, e);
		}
	}

	private void stopHttpClient() {
		HttpClient current = httpClient;
		httpClient = null;
		if (current == null) {
			return;
		}
		try {
			current.stop();
		} catch (Exception e) {
			logger.warn("failed to stop the http client of " + name, e);
		}
	}

	@Override
	public void onClose(CloseReason reason) {
		logger.info("websocket client " + name + " closed: " + reason.getReasonPhrase());
		disconnected.release();
	}

	@Override
	public void onError(Throwable thr) {
		if (closed && thr instanceof ClosedChannelException) {
			// don't print stack trace when ServerLauncher is closed
			logger.debug(
					"websocket client " + name + " error: " + thr.getClass().getSimpleName() + " " + thr.getMessage());
		} else {
			logger.warn("websocket client " + name + " error: " + thr.getMessage(), thr);
		}
		disconnected.release();
	}
}
