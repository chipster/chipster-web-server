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

	/*
	 * More generous than a local network needs, because failing here means not
	 * connecting at all: either end can be slow to get to the pong when a
	 * container is short of CPU or in a long GC. Still bounded, because
	 * SessionDbClient.subscribe() runs on a service's startup thread and a
	 * wedged server would stall it for this long per attempt. Costs nothing
	 * when the server answers or rejects us, as the wait ends at whichever
	 * comes first.
	 */
	private static final int PONG_TIMEOUT_S = 10;
	/*
	 * How long to wait for a close to be acknowledged, as master's
	 * waitForDisconnect(1) allowed. Used both when shutting down, so the
	 * server sees a clean close, and when a connect attempt fails, where the
	 * close reason is the only thing that names a refusal. It only ever
	 * delays an attempt that has already failed.
	 */
	private static final int CLOSE_TIMEOUT_MS = 1000;
	private static final int WAKEUP_INTERVAL_S = 30;
	private static final int SHUTDOWN_TIMEOUT_S = 5;

	private final String name;
	private final String uri;
	private final Whole<String> messageHandler;
	private final CredentialsProvider credentials;
	// null when this client shouldn't reconnect
	private final RetryHandler retryHandler;

	/*
	 * The session and the endpoint of one connection, published together: read
	 * separately they could belong to different attempts, and a caller would
	 * end up watching one connection while using another.
	 */
	private record Connection(Session session, WebSocketClientEndpoint endpoint) {
	}

	/*
	 * Written by the connecting thread (the constructor's caller, and after
	 * that the reconnect thread) and read from anywhere, hence volatile. The
	 * reconnect thread has exited by the time shutdown() closes them, unless
	 * it had to be abandoned - see shutdown().
	 */
	private volatile WebSocketContainer container;
	private volatile Connection connection;

	// a permit means "the connection may be gone, go and check"
	private final Semaphore disconnected = new Semaphore(0);
	// counted down once, by shutdown(), to end any wait in progress
	private final CountDownLatch shuttingDown = new CountDownLatch(1);
	private volatile boolean closed = false;
	private volatile Thread reconnectThread;

	public WebSocketClient(final String uri, final Whole<String> messageHandler, boolean retry, final String name,
			CredentialsProvider credentials)
			throws InterruptedException, WebSocketErrorException, WebSocketClosedException {

		this.name = name;
		this.uri = uri;
		this.messageHandler = messageHandler;
		this.credentials = credentials;

		/*
		 * Reconnecting is done here because the websocket container doesn't do
		 * it: Jetty's client has no reconnect of its own. It would not be much
		 * use anyway, as every attempt has to build its URL again to pick up a
		 * token that may have been renewed since the last one.
		 */
		this.retryHandler = retry ? new RetryHandler() : null;

		try {
			// no retrying here, even when retry is on: a service that can't
			// reach its server at startup should fail and let Kubernetes
			// restart it, rather than come up half-working
			connect();
		} catch (Throwable e) {
			/*
			 * Nobody will call shutdown() on an object whose constructor threw,
			 * and the HttpClient's threads are not daemons, so leaving them
			 * running would keep the JVM alive after a failed startup: the
			 * service would hang instead of exiting for Kubernetes to restart
			 * it. Throwable rather than the three checked types, because
			 * credentials.getPassword() renews the token and can throw
			 * unchecked when auth is itself still starting.
			 */
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

		// the previous attempt's session, if any. The container is
		// deliberately kept running and reused: building a new one for every
		// reconnect is what used to leak a whole thread pool each time
		closeSession(0);

		if (container == null) {
			try {
				/*
				 * Without an HttpClient of ours: Jetty then creates one and,
				 * because it isn't started yet, installs it as a managed bean,
				 * so stopping the container stops it too. One container for
				 * this client's lifetime is what fixes the leak - the old code
				 * built a new one per reconnect and stopped neither.
				 */
				container = JakartaWebSocketClientContainerProvider.getContainer(null);
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
		}
		WebSocketClientEndpoint newEndpoint = new WebSocketClientEndpoint(messageHandler, this);

		UriBuilder uriBuilder = UriBuilder.fromUri(this.uri);
		if (credentials != null) {
			uriBuilder = uriBuilder.queryParam("token", credentials.getPassword().toString());
		}

		logger.info("websocket client " + name + " connecting to " + uri);

		Session newSession;
		try {
			newSession = container.connectToServer(newEndpoint, ClientEndpointConfig.Builder.create().build(),
					new URI(uriBuilder.toString()));
		} catch (Exception e) {
			throw new WebSocketErrorException(e);
		}

		Connection attempt = new Connection(newSession, newEndpoint);
		try {
			verifyConnection(attempt);
		} catch (Throwable e) {
			// Not published yet, so nothing else would ever close it - and it
			// still has the caller's message handler attached, so leaving it
			// open would deliver every event twice once we reconnect.
			// Throwable, because Jetty throws unchecked from a session it is
			// tearing down under us
			closeQuietly(newSession);
			throw e;
		}

		// only now: being connected means the server answered our ping, so
		// isConnected() can't report a connection that is still being
		// checked, or one that turned out to be refused
		connection = attempt;

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
	private void verifyConnection(Connection attempt)
			throws WebSocketErrorException, WebSocketClosedException, InterruptedException {
		try {
			ping(attempt);
		} catch (InterruptedException e) {
			throw e;
		} catch (Throwable e) {
			/*
			 * Throwable, because Jetty throws unchecked from a session the
			 * server is closing under us.
			 *
			 * A server that refuses us closes the connection, and its close
			 * reason is the one thing that says why - much better to report
			 * than the ping failure it caused. It can be a moment behind
			 * though: Jetty stops the session's output before it delivers
			 * onClose(), and the ping gives up as soon as it sees that. Short:
			 * it is a thread hand-off we are waiting for, nothing more.
			 */
			CloseReason reason = awaitCloseReason(attempt.endpoint(), CLOSE_TIMEOUT_MS);
			if (reason != null) {
				throw new WebSocketClosedException(reason);
			}
			throw new WebSocketErrorException(e);
		}
	}

	private CloseReason awaitCloseReason(WebSocketClientEndpoint endpoint, long millis) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
		while (endpoint.getCloseReason() == null && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		return endpoint.getCloseReason();
	}

	private void reconnectLoop() {
		while (!closed) {
			try {
				// a bounded wait, not a plain acquire(): the signal is only a
				// hint, so a missed one - a close delivered while the session
				// still reads as open - is noticed here instead of leaving the
				// client silent for good. It can only re-read the session, so
				// a connection lost without a close (a node disappearing) is
				// still not detected; that would need a keepalive ping
				disconnected.tryAcquire(WAKEUP_INTERVAL_S, TimeUnit.SECONDS);
				// onClose() and onError() both signal the same disconnect, and
				// so does every failed attempt below - one round of
				// reconnecting covers them all, and isConnected() rejects
				// false alarms
				disconnected.drainPermits();

				if (!isConnected()) {
					reconnect();
				}
			} catch (Throwable t) {
				// connect() can fail with unchecked exceptions too, e.g. when
				// credentials.getPassword() renews the token from an auth
				// service that is itself still restarting. Letting one out
				// would end this thread, and with it every future reconnect of
				// this client, without so much as a log line
				if (closed) {
					// shutdown() pulled the resources out from under this
					// attempt; nothing wrong, and the loop is about to end
					logger.debug("websocket client " + name + " reconnect attempt failed while shutting down", t);
				} else {
					logger.error("websocket client " + name + " failed to reconnect, trying again", t);
				}
				// no callback will signal us again, so keep this loop going
				disconnected.release();
			}
		}
		/*
		 * Runs on every shutdown of a retry-enabled client, not only when
		 * shutdown() gave up waiting for this thread - whatever it started is
		 * this thread's to release either way. Nothing interrupts this thread,
		 * so Jetty's stop below can't be abandoned half-way; see sleep().
		 */
		closeResources();
		logger.debug("websocket client " + name + " reconnect thread finished");
	}

	/**
	 * Retry until connected, until the server tells us not to, or until
	 * shutdown(). Runs only on the reconnect thread.
	 */
	private void reconnect() {
		while (!closed) {

			long delay = retryHandler.nextDelaySeconds();
			logger.info("websocket client " + name + " reconnecting in " + delay + " s");
			if (!sleep(delay)) {
				return;
			}

			try {
				connect();
				return;
			} catch (WebSocketErrorException | WebSocketClosedException | InterruptedException e) {
				if (closed) {
					// shutdown() pulled the resources out from under this
					// attempt, having just logged that it would
					logger.debug("websocket client " + name + " attempt failed while shutting down", e);
					return;
				}
				/*
				 * Keep trying whatever the reason, including a server that
				 * refuses us: that is what a restarting session-db looks like
				 * while it is asking auth to validate our token, and giving up
				 * on it would silently end this client's event stream for the
				 * life of the process. Credentials that are simply wrong never
				 * get here - they fail the connect in the constructor, so the
				 * service doesn't start at all.
				 */
				logger.warn("websocket client " + name + " reconnection failed", e);
			}
		}
	}

	/*
	 * Waits out the retry delay. False if shutdown() cut it short, meaning the
	 * caller should give up.
	 *
	 * Waits on a latch of its own, not on the disconnect semaphore: that one
	 * is released by the endpoint callbacks too, so the attempt that just
	 * failed would cancel its own backoff and a refusing or restarting server
	 * would be retried as fast as a handshake takes. And not by sleeping
	 * either, so shutdown() never has to interrupt this thread - it also runs
	 * the resource cleanup on its way out, and Jetty abandons the rest of a
	 * stop on an InterruptedException.
	 */
	private boolean sleep(long seconds) {
		try {
			shuttingDown.await(seconds, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			// nothing interrupts this thread, but await declares it
			logger.warn("websocket client " + name + " interrupted while waiting to reconnect", e);
		}
		return !closed;
	}

	public boolean isConnected() {
		Connection current = connection;
		return current != null && current.session().isOpen();
	}

	public void sendText(String text) throws InterruptedException, IOException {
		Connection current = connection;
		if (current == null || !current.session().isOpen()) {
			throw new IOException("not connected");
		}
		current.session().getBasicRemote().sendText(text);
	}

	/*
	 * Pings the connection this client is using. Never the one connect() is
	 * still verifying: that one isn't published until it has answered.
	 *
	 * Jetty can throw unchecked from a session that closes between the check
	 * below and the send. Left alone deliberately: verifyConnection() catches
	 * Throwable, and no production code calls this - it is test support.
	 */
	public void ping() throws IOException, TimeoutException, InterruptedException {
		Connection current = connection;
		if (current == null) {
			throw new IOException("not connected");
		}
		ping(current);
	}

	private void ping(Connection attempt) throws IOException, TimeoutException, InterruptedException {
		Session current = attempt.session();
		WebSocketClientEndpoint currentEndpoint = attempt.endpoint();
		if (!current.isOpen()) {
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
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PONG_TIMEOUT_S);
			while (!pong.await(50, TimeUnit.MILLISECONDS)) {
				// nothing left to wait for once the connection is gone: a
				// server that rejects us closes it, and an error that killed
				// it leaves it not open. Deliberately not onError() itself,
				// which also fires when the caller's own message handler
				// throws on a connection that is fine
				if (currentEndpoint.getCloseReason() != null || !current.isOpen()) {
					throw new IOException("connection closed while waiting for pong");
				}
				if (closed) {
					// shutdown() shouldn't have to wait out this timeout: its
					// join would give up on us and clean up underneath
					throw new IOException("client closed while waiting for pong");
				}
				if (System.nanoTime() > deadline) {
					throw new TimeoutException("timeout while waiting for pong message");
				}
			}
		} finally {
			try {
				current.removeMessageHandler(pongHandler);
			} catch (Exception e) {
				// usually just a session closing under us, but if it isn't, the
				// handler stays registered and every later ping on this session
				// fails - too confusing to hide at debug
				logger.warn("websocket client " + name + " failed to remove the pong handler", e);
			}
		}
	}

	public void shutdown() throws IOException {
		logger.debug("shutdown websocket client " + name);

		closed = true;

		if (reconnectThread != null) {
			// the latch ends a retry delay, the permit an idle wait; between
			// them the thread needs no interrupt - see sleep()
			shuttingDown.countDown();
			disconnected.release();
			try {
				reconnectThread.join(TimeUnit.SECONDS.toMillis(SHUTDOWN_TIMEOUT_S));
			} catch (InterruptedException e) {
				// deliberately not handed back: the cleanup below has to run,
				// and Jetty abandons the rest of a stop on an interrupted
				// thread. This is a service on its way out anyway
				logger.warn("websocket client " + name + " interrupted while shutting down", e);
			}
			if (reconnectThread.isAlive()) {
				// Expected whenever the server is unreachable: the thread is
				// inside a connect attempt, which Jetty lets run longer than
				// the join above waits. Closing the resources below pulls them
				// out from under it, so its attempt fails and it releases
				// whatever it had started when it exits - hence info, not warn
				logger.info("websocket client " + name
						+ " reconnect thread still connecting, closing its resources anyway");
			}
		}

		closeResources();
	}

	/*
	 * Safe to call more than once: every field is null afterwards. Synchronized
	 * because shutdown() and the reconnect thread both reach it when a join
	 * times out, and each field here is a read-then-null that could otherwise
	 * interleave and leave a started container behind.
	 */
	private synchronized void closeResources() {
		// waits, unlike the reconnect path: the container is about to be
		// stopped, and it won't close sessions gracefully on the way out
		closeSession(CLOSE_TIMEOUT_MS);
		stopContainer();
	}

	/*
	 * Closes the current session, and optionally waits for the server to
	 * acknowledge it. Jetty only queues the close frame, so without the wait a
	 * shutdown tears the transport down first and the server logs an abrupt
	 * disconnect for every subscriber. A reconnect passes 0: that session is
	 * already gone, which is why we are reconnecting.
	 */
	private void closeSession(long awaitMillis) {
		Connection current = connection;
		connection = null;
		if (current == null) {
			return;
		}
		closeQuietly(current.session());
		if (awaitMillis > 0) {
			try {
				awaitCloseReason(current.endpoint(), awaitMillis);
			} catch (InterruptedException e) {
				logger.warn("websocket client " + name + " interrupted while closing", e);
			}
		}
	}

	private void closeQuietly(Session toClose) {
		try {
			if (toClose.isOpen()) {
				toClose.close(new CloseReason(CloseCodes.NORMAL_CLOSURE, "client closing"));
			}
		} catch (Exception e) {
			// Jetty 12.1.8 swallows everything here itself, so this is only
			// insurance for a future version: it runs first in
			// closeResources(), and one throw would skip stopping the rest
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
