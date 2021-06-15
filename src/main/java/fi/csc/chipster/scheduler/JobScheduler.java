package fi.csc.chipster.scheduler;

import java.util.UUID;

public interface JobScheduler {
	
	public void scheduleJob(UUID sessionId, UUID jobId);
	
	public void cancelJob(UUID sessionId, UUID jobId);

	public void removeFinishedJob(UUID sessionId, UUID jobId);
}
