package fi.csc.chipster.rest.websocket;

import java.util.concurrent.ConcurrentHashMap;

import jakarta.websocket.RemoteEndpoint.Basic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Topic {

	private static final Logger logger = LogManager.getLogger();

	private final ConcurrentHashMap<Basic, Subscriber> subscribers = new ConcurrentHashMap<>();

	public void add(Subscriber s) {
		subscribers.put(s.getRemote(), s);
		logger.debug("subscribers: " + subscribers.size());
	}

	public Subscriber remove(Basic basicRemote) {
		return subscribers.remove(basicRemote);
	}

	public boolean isEmpty() {
		return subscribers.isEmpty();
	}

	// No global ordering guarantee: two concurrent publish() calls may interleave enqueues,
	// so different subscribers can see messages in different orders.
	// Returns the number of subscribers that successfully accepted the message.
	public int publish(String msg) {
		int enqueued = 0;
		for (Subscriber s : subscribers.values()) {
			if (s.enqueue(msg)) {
				enqueued++;
			}
		}
		return enqueued;
	}

	public void ping() {
		for (Subscriber s : subscribers.values()) {
			s.enqueuePing();
		}
	}

	/**
	 * Stop all subscribers' sender threads. Does not remove them from the map —
	 * callers (currently only PubSubServer.stop()) should hold synchronized(topics)
	 * to prevent concurrent subscribe/unsubscribe during shutdown.
	 */
	public void stopAll() {
		for (Subscriber s : subscribers.values()) {
			s.stop();
		}
	}

	public ConcurrentHashMap<Basic, Subscriber> getSubscribers() {
		return subscribers;
	}
}
