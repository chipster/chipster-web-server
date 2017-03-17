package fi.csc.chipster.scheduler;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class JobSchedulingState {
	
		private LocalDateTime newTimestamp;
		private LocalDateTime scheduleTimestamp;
		private LocalDateTime runningTimestamp;
		private LocalDateTime runnableTimestamp;
		
		public JobSchedulingState() {
			setNewTimestamp();
		}
		
		public LocalDateTime getNewTimestamp() {
			return newTimestamp;
		}
		public void setNewTimestamp() {
			// set the new timestamp only once so that we can timeout it properly
			if (newTimestamp == null) {
				this.newTimestamp = LocalDateTime.now();
			}
		}
		public LocalDateTime getScheduleTimestamp() {
			return scheduleTimestamp;
		}
		public void setScheduleTimestamp() {
			this.scheduleTimestamp = LocalDateTime.now();
		}
		public LocalDateTime getRunningTimestamp() {
			return runningTimestamp;
		}
		public void setRunningTimestamp() {
			this.runningTimestamp = LocalDateTime.now();
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
			return newTimestamp.until(LocalDateTime.now(), ChronoUnit.SECONDS);
		}
		
		public long getTimeSinceScheduled() {
			return scheduleTimestamp.until(LocalDateTime.now(), ChronoUnit.SECONDS);
		}

		public long getTimeSinceLastHeartbeat() {
			return runningTimestamp.until(LocalDateTime.now(), ChronoUnit.SECONDS);
		}

		public void setRunnableTimestamp() {
			runnableTimestamp = LocalDateTime.now();
		}

		public boolean isRunnable() {
			return runnableTimestamp != null;
		}
	}