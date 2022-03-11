package fi.csc.chipster.scheduler.bash;

import java.time.Instant;

import fi.csc.chipster.toolbox.ToolboxTool;

public class BashJob {

	private Instant heartbeatTimestamp;
	private int slots;
	private ToolboxTool tool;

	public BashJob(int slots, ToolboxTool tool) {
		this.slots = slots;
		this.tool = tool;
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

	public ToolboxTool getTool() {
		return tool;
	}

	public void setTool(ToolboxTool tool) {
		this.tool = tool;
	}
}