package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint.Basic;
import jakarta.websocket.Session;

public class Subscriber {

	private static final Logger logger = LogManager.getLogger();
	private static final AtomicInteger threadCounter = new AtomicInteger();

	private sealed interface SendTask permits TextMessage, PingMessage {}
	private record TextMessage(String text) implements SendTask {}
	private record PingMessage() implements SendTask {}

	private static final PingMessage PING = new PingMessage();

	private final Session session;
	private final Basic remote;
	private final String username;
	private final Instant created;

	private final LinkedBlockingQueue<SendTask> queue;
	private final Thread senderThread;
	private final AtomicBoolean closing = new AtomicBoolean(false);

	// volatile: single writer (senderThread), read by unsubscribe() caller on a different thread.
	// single-writer volatile is sufficient for visibility — no atomics needed.
	// each volatile write happens-before the next volatile read of the same field, so the value
	// is current as of the sender's last write — but increments between that write and thread
	// termination may be missed if the thread is interrupted mid-flight. acceptable for monitoring
	// counters only; do not use these values for correctness decisions.
	private volatile int messagesSent;
	private volatile long bytesSent;
	// volatile: written by enqueue() caller, read by unsubscribe() caller on a different thread
	private volatile boolean queueFullDisconnect;

	public static Subscriber create(Session session, String username, int maxQueueSize) {
		Subscriber s = new Subscriber(session, username, maxQueueSize);
		s.senderThread.start();
		return s;
	}

	private Subscriber(Session session, String username, int maxQueueSize) {
		this.session = session;
		this.remote = session.getBasicRemote();
		this.username = username;
		this.created = Instant.now();
		this.queue = new LinkedBlockingQueue<>(maxQueueSize);
		this.senderThread = Thread.ofVirtual()
				// behind a proxy all connections share the same socket IP; the counter makes names unique
				.name("ws-sender-" + threadCounter.getAndIncrement() + "-" + PubSubConfigurator.remoteAddress(session))
				.unstarted(this::sendLoop);
	}

	private void sendLoop() {
		try {
			while (true) {
				SendTask task = queue.take();
				if (task instanceof TextMessage m) {
					// Jetty's write path (Jetty 12.1.8) uses synchronized internally, which pins
					// this virtual thread to its carrier thread for the duration of the send.
					// Other subscribers are unaffected (each has its own virtual thread), but under
					// many concurrent slow clients the ForkJoinPool carrier threads could be saturated.
					// Upgrading to Java 24+ (JEP 491) eliminates synchronized-based pinning entirely.
					remote.sendText(m.text());
					messagesSent++;
					bytesSent += m.text().length();
				} else {
					// allocate fresh: a shared static ByteBuffer would race on its mutable position;
					// IOException here is caught below and closes the session, same as for sendText
					remote.sendPing(ByteBuffer.allocate(0));
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException | RuntimeException e) {
			logger.warn("WebSocket send failed for {}, closing connection: {}", clientAddress(), e.getMessage());
			// compareAndSet ensures we call session.close() at most once from this side;
			// Jetty may concurrently tear down the session (triggering onClose/onError),
			// but Jetty handles a redundant close gracefully.
			if (closing.compareAndSet(false, true)) {
				try {
					session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "send error"));
				} catch (IOException e2) {
					logger.warn("failed to close WebSocket session for {}", clientAddress(), e2);
				}
			}
		}
	}

	/**
	 * Enqueue a text message for sending. Returns false and closes the connection
	 * if the queue is full.
	 */
	public boolean enqueue(String msg) {
		if (closing.get()) {
			return false;
		}
		if (queue.offer(new TextMessage(msg))) {
			return true;
		}
		// queue full — close once; note that a genuinely slow client will reconnect
		// and fill the queue again, potentially looping. add client-side backoff or
		// a server-side reconnect rate limit if this becomes a problem in production.
		// there is also a narrow TOCTOU race: enqueue() reads closing=false, the sender
		// thread sets closing=true and exits, then offer() succeeds — leaving a message
		// in a dead queue until the subscriber is GC'd. bounded by maxQueueSize, acceptable.
		if (closing.compareAndSet(false, true)) {
			queueFullDisconnect = true;
			logger.warn("WebSocket send queue full for {}, closing connection", clientAddress());
			try {
				session.close(new CloseReason(CloseReason.CloseCodes.TRY_AGAIN_LATER, "send queue full"));
			} catch (IOException e) {
				logger.warn("failed to close slow WebSocket subscriber {}", clientAddress(), e);
			}
		}
		return false;
	}

	/**
	 * Enqueue a ping frame. Silently dropped if the queue is full or the subscriber is closing.
	 * A persistently slow client won't be detected here — they'll be caught by the queue-full
	 * close path on the next published message, or by the WebSocket idle timeout.
	 */
	public void enqueuePing() {
		if (closing.get()) {
			return;
		}
		if (!queue.offer(PING)) {
			logger.debug("ping dropped for {}: send queue full", clientAddress());
		}
	}

	/**
	 * Stop the sender thread. Idempotent: re-interrupting a dead or already-interrupted thread is a no-op.
	 * Asynchronous: if the thread is blocked in sendText(), the interrupt flag is set but the thread
	 * is not unblocked until sendText() returns normally. It then loops back to queue.take(), which
	 * throws InterruptedException and exits the loop.
	 * Does not close the session — that is the caller's responsibility (Jetty server.stop(),
	 * onClose, or the onOpen error handler).
	 * Messages not yet dequeued when stop() is called may be dropped — the session is already
	 * closing so delivery is no longer possible. Messages already dequeued and mid-sendText()
	 * will still be sent before the thread exits.
	 */
	public void stop() {
		closing.set(true);
		senderThread.interrupt();
	}

	public Basic getRemote() {
		return remote;
	}

	public Session getSession() {
		return session;
	}

	// not cached: only called from error paths and the status JSON endpoint,
	// so the userProperties lookup cost is negligible
	public String getRemoteAddress() {
		return PubSubConfigurator.remoteAddress(session);
	}

	public String getUsername() {
		return username;
	}

	public Instant getCreated() {
		return created;
	}

	public int getMessagesSent() {
		return messagesSent;
	}

	public long getBytesSent() {
		return bytesSent;
	}

	public boolean isQueueFullDisconnect() {
		return queueFullDisconnect;
	}

	public String clientAddress() {
		return PubSubConfigurator.clientAddress(session);
	}
}
