package fi.csc.chipster.scheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class SchedulerWorkflowRuns {
	
	HashMap<IdPair, JobSchedulingState> runs = new HashMap<>();
	
	public Map<IdPair, JobSchedulingState> getRunning() {
		Map<IdPair, JobSchedulingState> runningJobs = runs.entrySet().stream()
				.filter(entry -> entry.getValue().isRunning())
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		return runningJobs;
	}

	public Map<IdPair, JobSchedulingState> getNew() {
		Map<IdPair, JobSchedulingState> newJobs = runs.entrySet().stream()
				.filter(entry -> entry.getValue().isNew())
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		return newJobs;
	}	

	public void remove(IdPair jobId) {
		runs.remove(jobId);	
	}

	public JobSchedulingState addNew(IdPair idPair, String userId) {
		JobSchedulingState jobState = new JobSchedulingState(userId, -1);
		runs.put(idPair, jobState);
		return jobState;
	}
	
	public void addRunning(IdPair idPair, String userId) {
		JobSchedulingState job = new JobSchedulingState(userId, -1);
		job.setScheduleTimestamp();
		job.setRunningTimestamp();
		runs.put(idPair, job);
	}

	public JobSchedulingState get(IdPair jobIdPair) {
		return runs.get(jobIdPair);
	}

	public boolean contains(UUID jobId) {
		return runs.keySet().stream()
				.map(p -> p.getJobId())
				.anyMatch(id -> jobId.equals(id));
	}
}
