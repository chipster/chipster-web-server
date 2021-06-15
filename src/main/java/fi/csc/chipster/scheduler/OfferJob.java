package fi.csc.chipster.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class OfferJob {

	//		private Instant newTimestamp;
	private Instant lastScheduleTimestamp;
	private Instant heartbeatTimestamp;
	private Instant runnableTimestamp;
	//		private String userId;
	//		private Instant userLimitReachedTimestamp;
	//		private int slots;

	//		public JobSchedulingState(String userId, int slots) {
	//			setNewTimestamp();
	//			this.userId = userId;
	//			this.slots = slots;
	//		}

	//		public Instant getNewTimestamp() {
	//			return newTimestamp;
	//		}
	//		public void setNewTimestamp() {
	//			// set the new timestamp only once so that we can timeout it properly
	//			if (newTimestamp == null) {
	//				this.newTimestamp = Instant.now();
	//			}
	//		}
	
	public OfferJob() {
		this.lastScheduleTimestamp = Instant.now();
	}
	
	public Instant getLastScheduleTimestamp() {
		return lastScheduleTimestamp;
	}
	
//	public void setScheduleTimestamp() {
//		this.scheduleTimestamp = Instant.now();
//	}
	
	public Instant getHeartbeatTimestamp() {
		return heartbeatTimestamp;
	}
	
	public void setHeartbeatTimestamp() {
		this.heartbeatTimestamp = Instant.now();
	}		

	//		public void setUserLimitReachedTimestamp(boolean isReached) {
	//			if (isReached) {
	//				this.userLimitReachedTimestamp = Instant.now();
	//			} else {
	//				this.userLimitReachedTimestamp = null;
	//			}
	//		}

	/**
	 * Check if the job is in SCHEDULED state, i.e. doesn't have heartbeat yet
	 * 
	 * @return
	 */
	public boolean isScheduled() {
		return heartbeatTimestamp == null;
		//			return scheduleTimestamp != null && heartbeatTimestamp == null;
	}

	public boolean hasHeartbeat() {
		return heartbeatTimestamp != null;
	}

//	public void removeScheduled() {
//		scheduleTimestamp = null;
//	}

//	public boolean isNew() {
//		return scheduleTimestamp == null && heartbeatTimestamp == null;
//	}
//
//	public long getTimeSinceNew() {
//		return newTimestamp.until(Instant.now(), ChronoUnit.SECONDS);
//	}

	public long getTimeSinceLastScheduled() {
		return lastScheduleTimestamp.until(Instant.now(), ChronoUnit.SECONDS);
	}

	public long getTimeSinceLastHeartbeat() {
		return heartbeatTimestamp.until(Instant.now(), ChronoUnit.SECONDS);
	}

	public void setRunnableTimestamp() {
		runnableTimestamp = Instant.now();
	}

	public boolean isRunnable() {
		return runnableTimestamp != null;
	}

	//		public boolean isUserLimitReached() {
	//			return userLimitReachedTimestamp != null;
	//		}
	//
	//		public String getUserId() {
	//			return userId;
	//		}
	//
	//		public int getSlots() {
	//			return slots;
	//		}
}