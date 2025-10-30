package fi.csc.chipster.scheduler;

import java.time.Instant;

import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.chipster.toolbox.runtime.Runtime;

public interface JobScheduler {

	public void scheduleJob(IdPair idPair, int slots, Integer storage, ToolboxTool toolboxTool, Runtime toolboxRuntime);

	public void cancelJob(IdPair idPair);

	public void removeFinishedJob(IdPair idPair);

	public Instant getLastHeartbeat(IdPair idPair);

	public long getHeartbeatInterval();

	public void addRunningJob(IdPair idPair, int slots, Integer storage, ToolboxTool tool, Runtime runtime);

	public String getLog(IdPair jobIdPair);

	public void logCompLog(IdPair jobIdPair, String compLog);
}
