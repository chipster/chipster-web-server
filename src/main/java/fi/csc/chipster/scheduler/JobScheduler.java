package fi.csc.chipster.scheduler;

import java.time.Instant;

public interface JobScheduler {
	
	public void scheduleJob(IdPair idPair, int slots, String image);
	
	public void cancelJob(IdPair idPair);

	public void removeFinishedJob(IdPair idPair);

	public Instant getLastHeartbeat(IdPair idPair);
	
	public long getHeartbeatInterval();

	public void addRunningJob(IdPair idPair, int slots);

	public String getLog(IdPair jobIdPair);
}
