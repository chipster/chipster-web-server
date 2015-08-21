package fi.csc.chipster.rest;

import java.util.concurrent.ConcurrentLinkedQueue;

import javax.ws.rs.client.WebTarget;

import org.glassfish.jersey.media.sse.EventInput;
import org.glassfish.jersey.media.sse.EventListener;
import org.glassfish.jersey.media.sse.EventSource;
import org.glassfish.jersey.media.sse.InboundEvent;

public class AsyncEventInput {

	private EventSource eventSource;
	
	ConcurrentLinkedQueue<InboundEvent> messages = new ConcurrentLinkedQueue<>();

	public AsyncEventInput(WebTarget target, String path, String eventName) {
		 
		// EventSource hides all errors, so connect first with EventInput to check
		// that server accepts this request
		EventInput eventInput = target.path(path).request().get(EventInput.class);
		eventInput.close();

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
	
	public InboundEvent pollAndWait() throws InterruptedException {
		for (int i = 0; i < 10; i++) {
			InboundEvent event = poll();
			if (event != null) {
				return event;
			}
			Thread.sleep(100);
		}
		return null;
	}
}
