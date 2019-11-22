package fi.csc.chipster.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.model.WorkflowInput;
import fi.csc.chipster.sessiondb.model.WorkflowJob;
import fi.csc.chipster.sessiondb.model.WorkflowState;
import fi.csc.microarray.messaging.JobState;

public class WorkflowSchedulingState {
	
		private String userId;
		private HashMap<UUID, JobState> jobs = new HashMap<>();

		private List<WorkflowJob> workflowJobs;
		private WorkflowState state;
		private HashMap<WorkflowState, Instant> stateTimestamps = new HashMap<>();
		private String stateDetail;
		
		public WorkflowSchedulingState(String userId) {
			this.userId = userId;
		}
				
		public long getTimeSince(WorkflowState state) {
			if (stateTimestamps.containsKey(state)) {
				return stateTimestamps.get(state).until(Instant.now(), ChronoUnit.SECONDS);
			}
			return -1;
		}		
		
		public Instant getTimestamp(WorkflowState state) {
			return stateTimestamps.get(state);
		}

		public String getUserId() {
			return userId;
		}

		public void putJobId(UUID jobId, JobState state) {
			jobs.put(jobId, state);
		}
		
		public boolean containsJobId(UUID jobId) {
			return jobs.containsKey(jobId);
		}

		public void removeJobId(UUID jobId) {
			jobs.remove(jobId);
		}

		public HashMap<UUID, JobState> getJobs() {
			return jobs;
		}
		
		public Map<UUID, JobState> getCompletedJobs() {
			return jobs.entrySet().stream()
			.filter(entry -> entry.getValue() == JobState.COMPLETED)
			.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		}
		
		public Map<UUID, JobState> getUnfinishedJobs() {
			return jobs.entrySet().stream()
			.filter(entry -> !entry.getValue().isFinished())
			.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		}

		public void setWorkflowJobs(List<WorkflowJob> workflowJobs) {
			this.workflowJobs = workflowJobs;
		}
		
		public List<WorkflowJob> getUnstartedJobs() {
			
			return workflowJobs.stream()
				.filter(workflowJob -> workflowJob.getJobId() == null)
				.collect(Collectors.toList());
		}
		
		/**
		 * Get jobs that haven't started yet but are ready to run
		 * 
		 * @param run
		 * @param schedulingState
		 * @return
		 * @throws RestException
		 */
		public List<WorkflowJob> getOperableJobs() {
						
			List<WorkflowJob> operableJobs = getUnstartedJobs().stream()
				.filter(workflowJob -> {
				
				List<WorkflowInput> readyInputs = workflowJob.getInputs().stream()
					.filter(input -> input.getDatasetId() != null)
					.collect(Collectors.toList());
				
				// check if all inputs are ready in this job
				return readyInputs.size() == workflowJob.getInputs().size();
				
			}).collect(Collectors.toList());
			
			return operableJobs;
		}

		public List<WorkflowJob> getWorkflowJobs() {
			return workflowJobs;
		}

		public void setState(WorkflowState state) {
			this.state = state;
			this.stateDetail = null;
			
			if (!stateTimestamps.containsKey(state)) {
				stateTimestamps.put(state, Instant.now());
			}
		}

		public WorkflowState getState() {
			return state;
		}

		public void setStateDetail(String stateDetail) {
			this.stateDetail = stateDetail;
		}

		public String getStateDetail() {
			return this.stateDetail;
		}
	}