package fi.csc.chipster.scheduler.bash;

import java.time.Instant;

import fi.csc.chipster.toolbox.ToolboxTool;

public class BashJob {

	private Instant heartbeatTimestamp;
	// slots are always set to make it easy to count quotas
	private int slots;
	// storage may be null, to stay consistent with the original request
	private Integer storage;

	private ToolboxTool tool;

	public BashJob(int slots, Integer storage, ToolboxTool tool) {
		this.slots = slots;
		this.tool = tool;
		this.storage = storage;
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

	public Integer getStorage() {
		return storage;
	}

	public void setStorage(Integer storage) {
		this.storage = storage;
	}
}