package fi.csc.chipster.sessionstorage.rest;

import java.util.logging.Logger;

import javax.inject.Singleton;
import javax.ws.rs.core.MediaType;

import org.glassfish.jersey.media.sse.EventOutput;
import org.glassfish.jersey.media.sse.OutboundEvent;
import org.glassfish.jersey.media.sse.SseBroadcaster;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessionstorage.model.SessionEvent;

@Singleton
public class Events {
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(Events.class.getName());

	// works only inside one JVM
	private static SseBroadcaster broadcaster = new SseBroadcaster();		
	
	public static void broadcast(SessionEvent sessionEvent) {
		OutboundEvent.Builder eventBuilder = new OutboundEvent.Builder();
		OutboundEvent event;

		// why the connection is closed if we use JSON_TYPE?
		event = eventBuilder.name("SessionEvent")
				//.mediaType(MediaType.APPLICATION_JSON_TYPE)
				//.data(SessionEvent.class, sessionEvent)
				.mediaType(MediaType.TEXT_PLAIN_TYPE)
				.data(String.class, RestUtils.asJson(sessionEvent))
				.build();

		broadcaster.broadcast(event);
	}

	public static EventOutput getEventOutput() {
		final EventOutput eventOutput = new EventOutput();
        //FIXME separate events of diffrent sessions
        Events.broadcaster.add(eventOutput);
        return eventOutput;
	}
}
