package fi.csc.chipster.rest.websocket;

import java.util.concurrent.ConcurrentHashMap;

import jakarta.websocket.RemoteEndpoint.Basic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Topic {

	private static final Logger logger = LogManager.getLogger();

	private ConcurrentHashMap<Basic, Subscriber> subscribers = new ConcurrentHashMap<>();

	public void add(Subscriber s) {
		subscribers.put(s.getRemote(), s);
		logger.debug("subscribers: " + subscribers.size());
	}

	public void remove(Basic basicRemote) {
		Subscriber s = subscribers.remove(basicRemote);
		if (s != null) {
			s.stop();
		}
	}

	public boolean isEmpty() {
		return subscribers.isEmpty();
	}

	public void publish(String msg) {
		for (Subscriber s : subscribers.values()) {
			s.enqueue(msg);
		}
	}

	public void ping() {
		for (Subscriber s : subscribers.values()) {
			s.enqueuePing();
		}
	}

	public ConcurrentHashMap<Basic, Subscriber> getSubscribers() {
		return subscribers;
	}
}
