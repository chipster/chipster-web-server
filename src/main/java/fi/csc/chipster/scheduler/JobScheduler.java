package fi.csc.chipster.scheduler;

import java.time.Instant;
import java.util.UUID;

public interface JobScheduler {
	
	public void scheduleJob(UUID sessionId, UUID jobId, int slots);
	
	public void cancelJob(UUID sessionId, UUID jobId);

	public void removeFinishedJob(UUID sessionId, UUID jobId);

	public Instant getLastHeartbeat(UUID sessionId, UUID jobId);
}
