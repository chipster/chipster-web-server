package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee10.websocket.jakarta.client.JakartaWebSocketClientContainerProvider;

import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.websocket.WebSocketClientEndpoint.EndpointListener;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler.Whole;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.ws.rs.core.UriBuilder;

public class WebSocketClient implements EndpointListener {

	public static final Logger logger = LogManager.getLogger();

	private static final int SHUTDOWN_LOCK_TIMEOUT_S = 10;

	/*
	 * Explicit state machine for the connection, all transitions made under
	 * stateLock:
	 *
	 * CONNECTING -> CONNECTED (connect() installs and ping-validates a session)
	 * CONNECTED -> CONNECTING (a disconnect is claimed for a retry)
	 * CONNECTING -> DISCONNECTED (retries exhausted without ever reconnecting)
	 * any -> CLOSED (shutdown(), terminal)
	 *
	 * `generation` is bumped on every transition. A connect() attempt captures
	 * the generation it was asked to pursue before doing its (unlocked)
	 * network I/O, then re-validates phase/generation under stateLock
	 * afterwards: if either changed while it wasn't looking (shutdown(), most
	 * likely), its result is stale and gets discarded instead of installed.
	 */
	private enum Phase {
		CONNECTING, CONNECTED, DISCONNECTED, CLOSED
	}

	private final ReentrantLock stateLock = new ReentrantLock();
	// all transitions (read-modify-write) happen under stateLock; volatile in
	// addition so onError()'s plain, unlocked read of it is never stale
	private volatile Phase phase = Phase.CONNECTING;
	private long generation = 0;

	private String name;
	private String uri;
	private Whole<String> messageHandler;
	private CredentialsProvider credentials;
	private RetryHandler retryHandler;

	// written under stateLock in connect(); also read from other threads
	// (sendText(), ping(), waitForConnection()) without it, so stays volatile
	private volatile WebSocketClientEndpoint endpoint;

	// written and read only under stateLock
	private Session session;
	private WebSocketContainer container;

	/*
	 * The one piece of state that deliberately survives across generations:
	 * reused so a reconnect doesn't have to spin up a brand new HttpClient
	 * (own thread pool, scheduler, etc.) every time. Only stopped when this
	 * client is shut down for good. The field is only touched under
	 * stateLock; stopping a detached one has to happen outside it, see
	 * stopHttpClient().
	 */
	private HttpClient httpClient;

	public WebSocketClient(final String uri, final Whole<String> messageHandler, boolean retry, final String name,
			CredentialsProvider credentials)
			throws InterruptedException, WebSocketErrorException, WebSocketClosedException {

		this.name = name;
		this.uri = uri;
		this.messageHandler = messageHandler;
		this.credentials = credentials;

		if (retry) {
			/*
			 * Handle retries in this class instead of letting Tyrus to do it
			 *
			 * Tyrus would try to reconnect always to the same URL, which won't work after
			 * the token has expired.
			 *
			 * RetryHandler could be given for the Tyrus like this:
			 * client.getProperties().put(ClientProperties.RECONNECT_HANDLER, retryHandler);
			 */
			this.retryHandler = new RetryHandler(name);
		}

		try {
			this.connect(0);
		} catch (WebSocketErrorException | InterruptedException | WebSocketClosedException e) {
			// this object is discarded by the caller and nothing will ever call
			// shutdown() on it, so stop retryHandler first: a concurrent
			// onClose()/onError() must not be able to start a reconnect loop
			// that nothing can ever stop again. Then stop whatever
			// httpClient/container connect() may have started before failing.
			if (retryHandler != null) {
				retryHandler.close();
			}
			HttpClient toStop = null;
			stateLock.lock();
			try {
				phase = Phase.CLOSED;
				generation++;
				toStop = closeResources();
			} finally {
				stateLock.unlock();
				stopHttpClient(toStop);
			}
			throw e;
		}
	}

	// expectedGeneration is the generation this attempt was asked to pursue,
	// captured by the caller (constructor or reconnect()) before any sleep or
	// other delay; used to detect a stale attempt after the unlocked network
	// I/O below
	private void connect(long expectedGeneration) throws WebSocketErrorException, InterruptedException,
			WebSocketClosedException {

		WebSocketClientEndpoint currentEndpoint;
		WebSocketContainer currentContainer;
		HttpClient failedClient = null;

		stateLock.lock();
		try {

			if (isStale(expectedGeneration)) {
				// shutdown() ran (or, in principle, a newer attempt already
				// moved past us) before we even started
				return;
			}

			// stop the previous connection's container wrapper, but keep the
			// underlying HttpClient (and its thread pool) running and reuse it
			stopContainer();

			// non-null means running: the catch below detaches one that
			// failed to start, and Jetty never stops an HttpClient on its own
			if (httpClient == null) {
				try {
					httpClient = new HttpClient();
					httpClient.start();
				} catch (Exception e) {
					// don't leave a partially-started httpClient (threads
					// already allocated) for a future connect() to clean up -
					// there might not be one, e.g. if retries are exhausted.
					// Detached here, stopped below once the lock is released
					failedClient = detachHttpClient();
					throw new WebSocketErrorException(e);
				}
			}

			try {
				container = JakartaWebSocketClientContainerProvider.getContainer(httpClient);
			} catch (Exception e) {
				// getContainer() also starts the container (its own thread
				// pool etc.) and rethrows a failure there as an unchecked
				// exception. Left alone, that would escape both the retry
				// loop in reconnect(), which only retries the checked
				// exceptions, and the constructor's catch, leaking the
				// httpClient we just started. Make it an ordinary connect
				// failure instead
				failedClient = detachHttpClient();
				throw new WebSocketErrorException(e);
			}
			currentContainer = container;

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

			// keep a local reference: a concurrent shutdown() could otherwise
			// read the field while/before we assign it
			currentEndpoint = endpoint = new WebSocketClientEndpoint(messageHandler, this);
		} finally {
			stateLock.unlock();
			stopHttpClient(failedClient);
		}

		final ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().build();

		// HTTP Basic authentication
		// client.getProperties().put(ClientProperties.CREDENTIALS, new
		// Credentials("ws_user", "password"));

		Session newSession;
		try {
			UriBuilder uriBuilder = UriBuilder.fromUri(this.uri);

			if (credentials != null) {
				uriBuilder = uriBuilder.queryParam("token", credentials.getPassword().toString());
			}

			logger.info("websocket client " + name + " connecting to " + uri);

			// Attempt Connect - the blocking handshake, done without stateLock.
			// A concurrent shutdown() may stop the shared httpClient while this
			// is in flight, which can surface as other exception types than the
			// JSR-356 ones (e.g. RejectedExecutionException), so catch broadly.
			newSession = currentContainer.connectToServer(currentEndpoint, cec, new URI(uriBuilder.toString()));
		} catch (Exception e) {
			throw new WebSocketErrorException(e);
		}

		HttpClient toStop = null;
		stateLock.lock();
		try {
			session = newSession;
			if (isStale(expectedGeneration)) {
				// shutdown() ran while we were connecting: it may have timed
				// out waiting for this connect() and skipped cleanup, so
				// finish that cleanup ourselves now, including this session
				toStop = closeResources();
				return;
			}
		} finally {
			stateLock.unlock();
			stopHttpClient(toStop);
		}

		// ping-validate outside the lock: this can take a couple of seconds
		// and must not block shutdown(). Only once this succeeds do we
		// consider the reconnect actually done - not right after the socket
		// handshake above - so a disconnect during this window is still
		// correctly seen as belonging to an in-flight attempt (phase still
		// CONNECTING), instead of racing a second, duplicate reconnect. The
		// flip side is that reconnect() ignores such a disconnect entirely,
		// so this attempt has to check for one itself before committing
		// CONNECTED below.
		try {
			currentEndpoint.waitForConnection();
		} catch (WebSocketErrorException | WebSocketClosedException | InterruptedException e) {
			// don't leave the half-open session/container dangling on the
			// server until the next attempt's stopContainer() incidentally
			// tears them down; httpClient is deliberately left alone so
			// it's still there to reuse for that next attempt
			stateLock.lock();
			try {
				if (!isStale(expectedGeneration)) {
					closeSessionAndContainer();
				}
			} finally {
				stateLock.unlock();
			}
			throw e;
		}

		stateLock.lock();
		try {
			if (isStale(expectedGeneration)) {
				// shutdown() ran during ping validation; finish its cleanup
				toStop = closeResources();
				return;
			}
			try {
				// the connection may have closed between the pong and here.
				// Its onClose()/onError() has then already run and been
				// ignored by reconnect() (phase was still CONNECTING), and
				// won't run again - so if we committed CONNECTED now, nothing
				// would ever reconnect this client. Fail the attempt instead,
				// so the caller's retry loop tries again.
				currentEndpoint.checkNotDisconnected();
			} catch (WebSocketClosedException | WebSocketErrorException e) {
				closeSessionAndContainer();
				throw e;
			}
			phase = Phase.CONNECTED;
			generation++;
		} finally {
			stateLock.unlock();
			stopHttpClient(toStop);
		}
	}

	// caller must hold stateLock
	private boolean isStale(long expectedGeneration) {
		return phase == Phase.CLOSED || generation != expectedGeneration;
	}

	// caller must hold stateLock
	private void stopContainer() {
		if (container != null) {
			try {
				JakartaWebSocketClientContainerProvider.stop(container);
			} catch (Exception e) {
				logger.warn("failed to stop the websocket container of " + name, e);
			}
			container = null;
		}
	}

	// Takes httpClient away from this client, so no later connect() or
	// shutdown() will touch it, and returns it for the caller to pass to
	// stopHttpClient() once stateLock has been released. Caller must hold
	// stateLock. Null if there was none.
	private HttpClient detachHttpClient() {
		HttpClient detached = httpClient;
		httpClient = null;
		return detached;
	}

	// Must be called WITHOUT holding stateLock. HttpClient.stop() waits (up
	// to Jetty's 5 s stop timeout) for the threads of its pool to finish, and
	// the reconnect loop runs on one of them - its finally block needs
	// stateLock to exit. Stopping under the lock would make that thread wait
	// for us while we wait for it: not a deadlock, the wait is bounded, but a
	// 5 s stall plus a "Couldn't stop Thread" warning from Jetty on every
	// shutdown() that lands while a reconnect is in progress. Calling this
	// from one of the pool's own threads (the DISCONNECTED path in
	// reconnect()) is fine, Jetty skips joining the current thread. Null-safe.
	private void stopHttpClient(HttpClient client) {
		if (client == null) {
			return;
		}
		try {
			client.stop();
		} catch (Exception e) {
			logger.warn("failed to stop the http client of " + name, e);
		}
	}

	/*
	 * For reconnection tests
	 */
	public void waitForConnection() throws InterruptedException, WebSocketClosedException, WebSocketErrorException {
		if (this.endpoint != null) {
			this.endpoint.waitForConnection();
		} else {
			throw new IllegalStateException("not connected");
		}
	}

	public void sendText(String text) throws InterruptedException, IOException {
		endpoint.sendText(text);
	}

	public void shutdown() throws IOException {
		logger.debug("shutdown websocket client " + name);

		if (retryHandler != null) {
			retryHandler.close();
		}

		// volatile: safe to read without the lock even if connect() is
		// concurrently replacing it
		WebSocketClientEndpoint currentEndpoint = endpoint;

		stateLock.lock();
		try {
			phase = Phase.CLOSED;
			generation++;
		} finally {
			stateLock.unlock();
		}

		try {
			// close() returns false if there was no open session (e.g. still
			// mid-handshake) - nothing to wait for a disconnect of then
			if (currentEndpoint != null && currentEndpoint.close() && !currentEndpoint.waitForDisconnect(1)) {
				logger.warn("failed to close the websocket client " + name);
			}
		} catch (IOException | InterruptedException e) {
			logger.warn("failed to close the websocket client " + name, e);
		}

		// wait (with a bound) for any in-flight reconnect to release the lock;
		// don't touch session/container/httpClient without it, that would
		// race the in-flight connect() that's holding it. connectToServer()
		// itself runs unlocked, so this can only time out if a session close
		// or container stop hangs on an unresponsive peer - rare, and Jetty's
		// own stop timeouts bound it well under SHUTDOWN_LOCK_TIMEOUT_S anyway
		boolean locked = false;
		try {
			locked = stateLock.tryLock(SHUTDOWN_LOCK_TIMEOUT_S, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (!locked) {
			logger.warn("timed out waiting for an in-flight reconnect of " + name
					+ " to finish, skipping cleanup to avoid racing it");
			return;
		}
		HttpClient toStop = null;
		try {
			toStop = closeResources();
		} finally {
			stateLock.unlock();
			// outside the lock, see stopHttpClient()
			stopHttpClient(toStop);
		}
	}

	// closes the session, stops the container and detaches httpClient; caller
	// must hold stateLock. Returns the detached httpClient (null if none),
	// which the caller must pass to stopHttpClient() after releasing
	// stateLock - see there for why it can't be stopped here. Safe to call
	// more than once (e.g. connect() and shutdown() both racing to clean
	// up): fields are null after the first.
	private HttpClient closeResources() {
		closeSessionAndContainer();
		return detachHttpClient();
	}

	// stops and clears session/container, deliberately leaving httpClient
	// running so it's still there to reuse; caller must hold stateLock
	private void closeSessionAndContainer() {
		try {
			if (session != null && session.isOpen()) {
				session.close();
			}
		} catch (IOException e) {
			// already closing/closed sessions can throw here; still fall through
			// to stop the container below
			logger.warn("failed to close the session of " + name, e);
		}
		session = null;

		stopContainer();
	}

	public void ping() throws IOException, TimeoutException, InterruptedException {
		endpoint.ping();
	}

	@Override
	public void onOpen(WebSocketClientEndpoint source, Session session, EndpointConfig config) {
		logger.info("websocket client " + name + " connected succesfully: " + uri);
		if (retryHandler != null) {
			retryHandler.reset();
		}
	}

	@Override
	public void onClose(WebSocketClientEndpoint source, Session session, CloseReason reason) {
		logger.info("websocket client " + name + " closed: " + reason.getReasonPhrase());
		reconnect(source, reason);
	}

	@Override
	public void onError(WebSocketClientEndpoint source, Session session, Throwable thr) {
		if (this.phase == Phase.CLOSED && thr instanceof ClosedChannelException) {
			// don't print stack trace when ServerLauncher is closed
			logger.debug(
					"websocket client " + name + " error: " + thr.getClass().getSimpleName() + " " + thr.getMessage());
		} else {
			logger.warn("websocket client " + name + " error: " + thr.getMessage(), thr);
		}

		// Jetty's FrameHandler contract guarantees onClose() always follows
		// onError() for an open session; the only documented exception is a
		// pre-handshake upgrade failure (Jetty's CoreClientUpgradeRequest.
		// handleException()), which calls only onError(), with no onClose()
		// ever following - that's the session == null case below. If the
		// session never opened, treat it as a close (reconnect() dedupes with
		// onClose())
		if (session == null || !session.isOpen()) {
			if (ExceptionUtils.getRootCause(thr) instanceof WebSocketClosedException) {
				// e.g. the server rejected the connection outright (policy violation);
				// same as onDisconnect()'s VIOLATED_POLICY case, don't retry
				logger.error("unrecoverable websocket close, reconnection cancelled");
				return;
			}
			reconnect(source, new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION,
					thr.getMessage() == null ? thr.getClass().getSimpleName() : thr.getMessage()));
		}
	}

	// shared retry path for onClose() and onError(); claims the current
	// generation so at most one reconnect starts per disconnected endpoint
	private void reconnect(WebSocketClientEndpoint source, CloseReason reason) {
		if (retryHandler == null) {
			return;
		}

		long myGeneration;
		stateLock.lock();
		try {
			// only CONNECTED means there's a live, un-claimed disconnect to
			// react to: CONNECTING means a reconnect for it is already in
			// flight (or the initial connect() hasn't finished yet),
			// DISCONNECTED/CLOSED mean nothing should be started at all.
			if (phase != Phase.CONNECTED) {
				return;
			}
			if (source != this.endpoint) {
				// stale: a (possibly delayed) callback for an endpoint a
				// later attempt has already superseded. Compared by
				// endpoint identity rather than session, since session is
				// null for a pre-Session upgrade failure and can't be
				// identity-compared in that case.
				return;
			}
			phase = Phase.CONNECTING;
			generation++;
			myGeneration = generation;
		} finally {
			stateLock.unlock();
		}

		try {
			// sleep outside the lock, so shutdown() isn't blocked for the delay
			while (true) {
				boolean shouldRetry;
				try {
					shouldRetry = retryHandler.onDisconnect(reason);
				} catch (RuntimeException e) {
					// onDisconnect() throws (rather than returning false) for
					// an unrecoverable close, e.g. a policy violation - stop
					// retrying instead of letting this escape onClose()/
					// onError(), which are plain container callbacks
					logger.error("unrecoverable websocket close, reconnection cancelled", e);
					break;
				}
				if (!shouldRetry) {
					break;
				}
				try {
					Thread.sleep(retryHandler.getDelay() * 1000);
					connect(myGeneration);
					return;
				} catch (WebSocketErrorException | InterruptedException | WebSocketClosedException e) {
					logger.error("error in reconnection", e);
				}
			}
		} finally {
			HttpClient toStop = null;
			stateLock.lock();
			try {
				// only true if every attempt failed and retries were
				// exhausted without connect() ever reaching CONNECTED (or
				// shutdown() running) in the meantime - don't clobber
				// whichever of those it actually landed on
				if (phase == Phase.CONNECTING && generation == myGeneration) {
					phase = Phase.DISCONNECTED;
					// nothing will ever call connect() or shutdown() for
					// this client again from here (onClose()/onError() both
					// refuse to react once phase isn't CONNECTED), so
					// release the shared resources ourselves now instead of
					// leaking them for the rest of the process's life
					toStop = closeResources();
				}
			} finally {
				stateLock.unlock();
				// outside the lock, and on one of the httpClient's own pool
				// threads - both fine, see stopHttpClient()
				stopHttpClient(toStop);
			}
		}
	}
}
