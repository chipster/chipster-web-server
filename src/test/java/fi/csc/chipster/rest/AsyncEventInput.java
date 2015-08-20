package fi.csc.chipster.rest;

import java.util.concurrent.ConcurrentLinkedQueue;

import javax.ws.rs.client.WebTarget;

import org.glassfish.jersey.media.sse.EventListener;
import org.glassfish.jersey.media.sse.EventSource;
import org.glassfish.jersey.media.sse.InboundEvent;

public class AsyncEventInput {

	private EventSource eventSource;
	
	ConcurrentLinkedQueue<InboundEvent> messages = new ConcurrentLinkedQueue<>();

	public AsyncEventInput(WebTarget target, String path, String eventName) {

		eventSource = EventSource.target(target.path(path)).build();
		EventListener listener = new EventListener() {
			@Override
			public void onEvent(InboundEvent inboundEvent) {
				messages.add(inboundEvent);
			}
		};
		eventSource.register(listener);
		eventSource.open();
	}
	
	public void close() {
		eventSource.close();
	}

	public InboundEvent poll() {
		return messages.poll();
	}
}
