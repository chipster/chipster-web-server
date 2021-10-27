package fi.csc.chipster.scheduler.bash;

import java.time.Instant;

public class BashJob {

	private Instant heartbeatTimestamp;
	private int slots;
	private String toolId;

	public BashJob(int slots, String toolId) {
		this.slots = slots;
		this.toolId = toolId;
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

	public String getToolId() {
		return toolId;
	}

	public void setToolId(String toolId) {
		this.toolId = toolId;
	}
}