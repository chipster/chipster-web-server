package fi.csc.chipster.scheduler.offer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class OfferJob {

	private Instant lastScheduleTimestamp;
	private Instant heartbeatTimestamp;
	private Instant runnableTimestamp;
	
	public OfferJob() {
		this.lastScheduleTimestamp = Instant.now();
	}
	
	public Instant getLastScheduleTimestamp() {
		return lastScheduleTimestamp;
	}
		
	public Instant getHeartbeatTimestamp() {
		return heartbeatTimestamp;
	}
	
	public void setHeartbeatTimestamp() {
		this.heartbeatTimestamp = Instant.now();
	}		

	/**
	 * Check if the job is in SCHEDULED state, i.e. doesn't have heartbeat yet
	 * 
	 * @return
	 */
	public boolean isScheduled() {
		return heartbeatTimestamp == null;
	}

	public boolean hasHeartbeat() {
		return heartbeatTimestamp != null;
	}

	public long getTimeSinceLastScheduled() {
		return lastScheduleTimestamp.until(Instant.now(), ChronoUnit.SECONDS);
	}

	public long getTimeSinceLastHeartbeat() {
		return heartbeatTimestamp.until(Instant.now(), ChronoUnit.SECONDS);
	}

	public void setRunnableTimestamp() {
		runnableTimestamp = Instant.now();
	}

	public boolean isRunnable() {
		return runnableTimestamp != null;
	}
}