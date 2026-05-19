package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint.Basic;
import jakarta.websocket.Session;

public class Subscriber {

	private static final Logger logger = LogManager.getLogger();

	private sealed interface SendTask permits TextMessage, PingMessage {}
	private record TextMessage(String text) implements SendTask {}
	private record PingMessage() implements SendTask {}

	private static final PingMessage PING = new PingMessage();

	private final Session session;
	private final Basic remote;
	private final String remoteAddress;
	private final String username;
	private final Instant created;
	private final Map<String, String> details;

	private final LinkedBlockingQueue<SendTask> queue;
	private final Thread senderThread;
	private final AtomicBoolean closing = new AtomicBoolean(false);

	public Subscriber(Session session, String remoteAddress, Map<String, String> details,
			String username, int maxQueueSize) {
		this.session = session;
		this.remote = session.getBasicRemote();
		this.remoteAddress = remoteAddress;
		this.details = details;
		this.username = username;
		this.created = Instant.now();
		this.queue = new LinkedBlockingQueue<>(maxQueueSize);
		this.senderThread = Thread.ofVirtual()
				.name("ws-sender-" + remoteAddress)
				.start(this::sendLoop);
	}

	private void sendLoop() {
		try {
			while (!Thread.currentThread().isInterrupted()) {
				SendTask task = queue.take();
				if (task instanceof TextMessage m) {
					remote.sendText(m.text());
				} else {
					remote.sendPing(ByteBuffer.allocate(0));
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			logger.debug("WebSocket send failed for {}, stopping sender thread: {}", remoteAddress, e.getMessage());
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
		// queue full — close once
		if (closing.compareAndSet(false, true)) {
			logger.warn("WebSocket send queue full for {}, closing connection", remoteAddress);
			try {
				session.close(new CloseReason(CloseReason.CloseCodes.TRY_AGAIN_LATER, "send queue full"));
			} catch (IOException e) {
				logger.warn("failed to close slow WebSocket subscriber {}", remoteAddress, e);
			}
		}
		return false;
	}

	/** Enqueue a ping frame. Silently dropped if the queue is full. */
	public void enqueuePing() {
		queue.offer(PING);
	}

	/** Stop the sender thread. Idempotent. */
	public void stop() {
		senderThread.interrupt();
	}

	public Basic getRemote() {
		return remote;
	}

	public Session getSession() {
		return session;
	}

	public String getRemoteAddress() {
		return remoteAddress;
	}

	public String getUsername() {
		return username;
	}

	public Instant getCreated() {
		return created;
	}

	public Map<String, String> getDetails() {
		return details;
	}
}
