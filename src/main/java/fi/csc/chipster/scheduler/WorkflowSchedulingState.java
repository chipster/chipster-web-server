package fi.csc.chipster.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class WorkflowSchedulingState {
	
		private Instant newTimestamp;
		private Instant runningTimestamp;
		private String userId;
		private Instant userLimitReachedTimestamp;
		private Set<UUID> jobIds = new HashSet<UUID>();
		
		public WorkflowSchedulingState(String userId) {
			setNewTimestamp();
			this.userId = userId;
		}
		
		public Instant getNewTimestamp() {
			return newTimestamp;
		}
		public void setNewTimestamp() {
			// set the new timestamp only once so that we can timeout it properly
			if (newTimestamp == null) {
				this.newTimestamp = Instant.now();
			}
		}
		
		public Instant getRunningTimestamp() {
			return runningTimestamp;
		}
		
		public void setRunningTimestamp() {
			this.runningTimestamp = Instant.now();
		}		
		
		public void setUserLimitReachedTimestamp(boolean isReached) {
			if (isReached) {
				this.userLimitReachedTimestamp = Instant.now();
			} else {
				this.userLimitReachedTimestamp = null;
			}
		}
				
		public boolean isRunning() {
			return runningTimestamp != null;
		}
		
		public long getTimeSinceNew() {
			return newTimestamp.until(Instant.now(), ChronoUnit.SECONDS);
		}
		
		public boolean isUserLimitReached() {
			return userLimitReachedTimestamp != null;
		}

		public String getUserId() {
			return userId;
		}

		public boolean putJobId(UUID jobId) {
			return jobIds.add(jobId);
		}
		
		public boolean containsJobId(UUID jobId) {
			return jobIds.contains(jobId);
		}

		public boolean removeJobId(UUID jobId) {
			return jobIds.remove(jobId);
		}

		public Set<UUID> getJobIds() {
			return jobIds;
		}
	}