package fi.csc.chipster.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class JobSchedulingState {
	
		private Instant newTimestamp;
		private Instant scheduleTimestamp;
		private Instant runningTimestamp;
		private Instant runnableTimestamp;
		private String userId;
		private Instant userLimitReachedTimestamp;
		private int slots;
		
		public JobSchedulingState(String userId, int slots) {
			setNewTimestamp();
			this.userId = userId;
			this.slots = slots;
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
		public Instant getScheduleTimestamp() {
			return scheduleTimestamp;
		}
		public void setScheduleTimestamp() {
			this.scheduleTimestamp = Instant.now();
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
		
		public boolean isScheduled() {
			return scheduleTimestamp != null && runningTimestamp == null;
		}
		
		public boolean isRunning() {
			return runningTimestamp != null;
		}

		public void removeScheduled() {
			scheduleTimestamp = null;
		}

		public boolean isNew() {
			return scheduleTimestamp == null && runningTimestamp == null;
		}
		
		public long getTimeSinceNew() {
			return newTimestamp.until(Instant.now(), ChronoUnit.SECONDS);
		}
		
		public long getTimeSinceScheduled() {
			return scheduleTimestamp.until(Instant.now(), ChronoUnit.SECONDS);
		}

		public long getTimeSinceLastHeartbeat() {
			return runningTimestamp.until(Instant.now(), ChronoUnit.SECONDS);
		}

		public void setRunnableTimestamp() {
			runnableTimestamp = Instant.now();
		}

		public boolean isRunnable() {
			return runnableTimestamp != null;
		}
		
		public boolean isUserLimitReached() {
			return userLimitReachedTimestamp != null;
		}

		public String getUserId() {
			return userId;
		}

		public int getSlots() {
			return slots;
		}
	}