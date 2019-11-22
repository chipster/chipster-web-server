package fi.csc.chipster.scheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import fi.csc.chipster.sessiondb.model.WorkflowRunIdPair;
import fi.csc.chipster.sessiondb.model.WorkflowState;

public class SchedulerWorkflowRuns {
	
	HashMap<WorkflowRunIdPair, WorkflowSchedulingState> runs = new HashMap<>();
	
	public Set<WorkflowRunIdPair> get(WorkflowState state) {
		return runs.entrySet().stream()
			.filter(entry -> entry.getValue().getState() == state)
			.map(entry -> entry.getKey())
			.collect(Collectors.toSet());
	}

	public WorkflowSchedulingState remove(WorkflowRunIdPair runIdPair) {
		return runs.remove(runIdPair);	
	}
		
	public void put(WorkflowRunIdPair runIdPair, WorkflowSchedulingState schedulingState) {		
		runs.put(runIdPair, schedulingState);
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

	public List<WorkflowSchedulingState> getByUserId(String createdBy) {
		return runs.values().stream()
			.filter(run -> createdBy.equals(run.getUserId()))
			.collect(Collectors.toList());
	}
}
