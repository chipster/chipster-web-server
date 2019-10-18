package fi.csc.chipster.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public class WorkflowSchedulingState {
	
		private Instant newTimestamp;
		private Instant runningTimestamp;
		private String userId;
		private Instant userLimitReachedTimestamp;
		private UUID currentJobId;
		
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
		
		public long getTimeSinceLastHeartbeat() {
			return runningTimestamp.until(Instant.now(), ChronoUnit.SECONDS);
		}
		
		public boolean isUserLimitReached() {
			return userLimitReachedTimestamp != null;
		}

		public String getUserId() {
			return userId;
		}

		public UUID getCurrentJobId() {
			return currentJobId;
		}

		public void setCurrentJobId(UUID currentJobId) {
			this.currentJobId = currentJobId;
		}
		
		public boolean isNew() {
			return runningTimestamp == null;
		}
	}