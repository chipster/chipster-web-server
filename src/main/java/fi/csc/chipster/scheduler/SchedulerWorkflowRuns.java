package fi.csc.chipster.scheduler;

import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import fi.csc.chipster.sessiondb.model.WorkflowRunIdPair;

public class SchedulerWorkflowRuns {
	
	HashMap<WorkflowRunIdPair, WorkflowSchedulingState> runs = new HashMap<>();
	
	public Set<WorkflowRunIdPair> getRunning() {
		return runs.entrySet().stream()
			.filter(entry -> entry.getValue().isRunning())
			.map(entry -> entry.getKey())
			.collect(Collectors.toSet());
	}

	public Set<WorkflowRunIdPair> getNew() {
		return runs.entrySet().stream()
				.filter(entry -> !entry.getValue().isRunning())
				.map(entry -> entry.getKey())
				.collect(Collectors.toSet());
	}	

	public WorkflowSchedulingState remove(WorkflowRunIdPair runIdPair) {
		return runs.remove(runIdPair);	
	}
		
	public void put(WorkflowRunIdPair runIdPair, WorkflowSchedulingState schedulingState) {		
		runs.put(runIdPair, schedulingState);
	}	

	public WorkflowRunIdPair getWorkflowRun(UUID sessionId, UUID jobId) {
		
		for (WorkflowRunIdPair runIdPair : runs.keySet()) {
			if (sessionId.equals(runIdPair.getSessionId())) {
				if (runs.get(runIdPair).containsJobId(jobId)) {
					return runIdPair;
				}
			}
		}
				
		return null;
	}

	public WorkflowSchedulingState get(WorkflowRunIdPair workflowRunIdPair) {
		return runs.get(workflowRunIdPair);
	}

	public WorkflowRunIdPair getWorkflowRunId(IdPair jobIdPair) {
		for (WorkflowRunIdPair runIdPair : runs.keySet()) {
			if (jobIdPair.getSessionId().equals(runIdPair.getSessionId())) {
				if (runs.get(runIdPair).containsJobId(jobIdPair.getJobId())) {
					return runIdPair;
				}
			}
		}
		return null;
	}
}
