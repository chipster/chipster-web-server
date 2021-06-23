package fi.csc.chipster.scheduler;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

	public void remove(IdPair jobId) {
		jobs.remove(jobId);	
	}

	public BashJob addJob(IdPair idPair, int slots) {
		BashJob jobState = new BashJob(slots);
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
