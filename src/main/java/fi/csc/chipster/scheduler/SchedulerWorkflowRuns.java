package fi.csc.chipster.scheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class SchedulerWorkflowRuns {
	
	HashMap<IdPair, WorkflowSchedulingState> runs = new HashMap<>();
	
	public Map<IdPair, WorkflowSchedulingState> getRunning() {
		Map<IdPair, WorkflowSchedulingState> runningJobs = runs.entrySet().stream()
				.filter(entry -> entry.getValue().isRunning())
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		return runningJobs;
	}

	public Map<IdPair, WorkflowSchedulingState> getNew() {
		Map<IdPair, WorkflowSchedulingState> newJobs = runs.entrySet().stream()
				.filter(entry -> entry.getValue().isNew())
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		return newJobs;
	}	

	public void remove(IdPair jobId) {
		runs.remove(jobId);	
	}

	public WorkflowSchedulingState addNew(IdPair idPair, String userId) {
		WorkflowSchedulingState schedulingState = new WorkflowSchedulingState(userId);
		runs.put(idPair, schedulingState);
		return schedulingState;
	}
	
	public void addRunning(IdPair idPair, String userId) {
		WorkflowSchedulingState schedulingState = new WorkflowSchedulingState(userId);
		schedulingState.setRunningTimestamp();
		runs.put(idPair, schedulingState);
	}

	public WorkflowSchedulingState get(IdPair jobIdPair) {
		return runs.get(jobIdPair);
	}

	public boolean contains(UUID jobId) {
		return runs.keySet().stream()
				.map(p -> p.getJobId())
				.anyMatch(id -> jobId.equals(id));
	}

	public IdPair getWorkflowRunId(UUID jobId) {
		Optional<Entry<IdPair, WorkflowSchedulingState>> optional = runs.entrySet().stream()
				.filter(entry -> jobId.equals(entry.getValue().getCurrentJobId()))
				.findAny();
		
		if (optional.isPresent()) {
			return optional.get().getKey();
		}
		return null;
	}
}
