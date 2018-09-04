package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.RemoteEndpoint.Basic;

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
		subscribers.remove(basicRemote);
	}

	public boolean isEmpty() {
		return subscribers.isEmpty();
	}

	public void publish(String msg) {
		// usage of the same remoteEndpoint isn't allowed from multiple endpoints
		// http://stackoverflow.com/questions/26264508/websocket-async-send-can-result-in-blocked-send-once-queue-filled
		synchronized (this) {
			logger.debug("publish to " + subscribers.size() + " subscribers: " + msg);
			for (Subscriber s: subscribers.values()) {
				try {
					logger.debug("send to " + s.getRemoteAddress());
					s.getRemote().sendText(msg);
				} catch (IOException e) {
					// nothing to worry about if the client just unsubscribed 
					logger.warn("failed to publish a message to: " + subscribers.get(s.getRemoteAddress()), e);
				}
			}
		}
	}
	
	public void ping() {
		synchronized (this) {
			logger.debug("ping " + subscribers.size() + " subscribers");
			for (Subscriber s: subscribers.values()) {
				try {
					logger.debug("send to " + s.getRemoteAddress());
					s.getRemote().sendPing(null);
				} catch (IOException e) {
					// nothing to worry about if the client just unsubscribed 
					logger.warn("failed to ping " + subscribers.get(s.getRemoteAddress()), e);
				}
			}
		}
	}

	public ConcurrentHashMap<Basic, Subscriber> getSubscribers() {
		return subscribers;
	}
}