package fi.csc.chipster.sessiondb.resource;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.media.sse.OutboundEvent;
import org.glassfish.jersey.media.sse.SseBroadcaster;
import org.glassfish.jersey.server.ChunkedOutput;

public class ManagedBroadcaster extends SseBroadcaster {
	
	private static final int WARN_SIZE = 1000;

	private static Logger logger = LogManager.getLogger();

	Queue<ChunkedOutput<OutboundEvent>> outputs = new ConcurrentLinkedQueue<>();
	
	@Override
	public <OUT extends ChunkedOutput<OutboundEvent>> boolean add(OUT event) {
		if (outputs.size() > WARN_SIZE) {
			// we need some kind of expiration or cleaning
			logger.warn(this.getClass().getSimpleName() + " has too many outputs: " + outputs.size());
		}
		outputs.add(event);
		return super.add(event);
	}
	
	@Override
	public void onClose(ChunkedOutput<OutboundEvent> chunkedOutput) {
		outputs.remove();
		super.onClose(chunkedOutput);
	}
	
	public boolean isEmpty() {
		return outputs.isEmpty();
	}
}
