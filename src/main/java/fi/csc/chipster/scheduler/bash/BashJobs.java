package fi.csc.chipster.scheduler.bash;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import fi.csc.chipster.scheduler.IdPair;
import fi.csc.chipster.toolbox.ToolboxTool;

public class BashJobs {
	
	HashMap<IdPair, BashJob> jobs = new HashMap<>();
	
	public Map<IdPair, BashJob> getHeartbeatJobs() {
		Map<IdPair, BashJob> runningJobs = jobs.entrySet().stream()
				.filter(entry -> entry.getValue().hasHeartbeat())
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		return runningJobs;
	}

	public Map<IdPair, BashJob> getAllJobs() {
		return jobs;
	}

	public static int getSlots(Collection<BashJob> jobs) {
		return jobs.stream()
				.mapToInt(j -> j.getSlots())
				.sum();
	}

	public BashJob remove(IdPair jobId) {
		return jobs.remove(jobId);	
	}

	public BashJob addJob(IdPair idPair, int slots, ToolboxTool tool) {
		BashJob jobState = new BashJob(slots, tool);
		
		/* Set first heartbeat to change this job from the state scheduled to running
		 * 
		 * Jobs are allowed to stay in the scheduled state only momentarily (a second or so), but we 
		 * cannot rely on heartbeat script to change the state, because it's not run frequently enough. 
		 */
		jobState.setHeartbeatTimestamp();		
		jobs.put(idPair, jobState);
		return jobState;
	}

	public BashJob get(IdPair jobIdPair) {
		return jobs.get(jobIdPair);
	}

	public boolean containsJobId(UUID jobId) {
		return jobs.keySet().stream()
				.map(p -> p.getJobId())
				.anyMatch(id -> jobId.equals(id));
	}
}
