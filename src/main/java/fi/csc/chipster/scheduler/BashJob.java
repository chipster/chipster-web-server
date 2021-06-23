package fi.csc.chipster.scheduler;

import java.time.Instant;

public class BashJob {

	private Instant heartbeatTimestamp;
	private int slots;

	public BashJob(int slots) {
		this.slots = slots;
	}
	
	public Instant getHeartbeatTimestamp() {
		return heartbeatTimestamp;
	}
	
	public void setHeartbeatTimestamp() {
		this.heartbeatTimestamp = Instant.now();
	}		

	public boolean hasHeartbeat() {
		return heartbeatTimestamp != null;
	}

	public int getSlots() {
		return slots;
	}
}