package fi.csc.chipster.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class SchedulerJob {
	
		private Instant newTimestamp;
		private Instant scheduleTimestamp;
		private Instant runningTimestamp;
		private String userId;
		private int slots;
		private String image;
		private String toolId;
		
		public SchedulerJob(String userId, int slots, String image, String toolId) {
			setNewTimestamp();
			this.userId = userId;
			this.slots = slots;
			this.image = image;
			this.toolId = toolId;
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
			return runningTimestamp == null && scheduleTimestamp == null;
		}
		
		public long getTimeSinceNew() {
			return newTimestamp.until(Instant.now(), ChronoUnit.SECONDS);
		}
		
		public long getTimeSinceScheduled() {
			return scheduleTimestamp.until(Instant.now(), ChronoUnit.SECONDS);
		}

		public String getUserId() {
			return userId;
		}

		public int getSlots() {
			return slots;
		}

		public String getImage() {
			return image;
		}

		public void setImage(String image) {
			this.image = image;
		}

		public String getToolId() {
			return toolId;
		}

		public void setToolId(String toolId) {
			this.toolId = toolId;
		}
	}