package fi.csc.chipster.sessiondb.resource;

import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.media.sse.EventOutput;
import org.glassfish.jersey.media.sse.OutboundEvent;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessiondb.model.SessionEvent;

public class Events {
	
	public static final String EVENT_NAME = "SessionEvent";

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	ConcurrentHashMap<UUID, ManagedBroadcaster> broadcasters = new ConcurrentHashMap<>();

	private String serverId;
	private volatile long eventNumber = 0;
	
	public Events(String serverId) {
		this.serverId = serverId;
	}

	public void broadcast(SessionEvent sessionEvent) {
				
		sessionEvent.setServerId(serverId);
		sessionEvent.setEventNumber(eventNumber);
		
		OutboundEvent.Builder eventBuilder = new OutboundEvent.Builder();
		OutboundEvent event;

		// why the connection is closed if we use JSON_TYPE?
		event = eventBuilder.name(EVENT_NAME)
				//.mediaType(MediaType.APPLICATION_JSON_TYPE)
				//.data(SessionEvent.class, sessionEvent)
				.mediaType(MediaType.TEXT_PLAIN_TYPE)
				.data(String.class, RestUtils.asJson(sessionEvent))
				.build();
		
		UUID sessionId = sessionEvent.getSessionId();

		if (sessionId == null) {
			throw new InternalServerErrorException("unable to send events when sessionId is null");
		}
		if (broadcasters.containsKey(sessionId)) {
			broadcasters.get(sessionId).broadcast(event);
		}
	}

	public EventOutput getEventOutput(UUID sessionId) {
		
		cleanUp();
		
		final EventOutput eventOutput = new EventOutput();
		if (!broadcasters.containsKey(sessionId)) {
			broadcasters.put(sessionId, new ManagedBroadcaster());
		}
		broadcasters.get(sessionId).add(eventOutput);
        return eventOutput;
	}

	private void cleanUp() {
		Iterator<UUID> iter = broadcasters.keySet().iterator();
		
		while (iter.hasNext()) {
			UUID id = iter.next();
			if (broadcasters.get(id).isEmpty()) {
				iter.remove();
			}
		}
	}

	public void setServerId(String serverId) {
		this.serverId = serverId;
	}

	public void close() {
		for (ManagedBroadcaster broadcaster : broadcasters.values()) {
			broadcaster.closeAll();
		}
	}
}