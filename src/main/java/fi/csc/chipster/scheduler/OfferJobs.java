package fi.csc.chipster.scheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class OfferJobs {
	
	HashMap<IdPair, OfferJob> jobs = new HashMap<>();
	
	public Map<IdPair, OfferJob> getHeartbeatJobs() {
		Map<IdPair, OfferJob> runningJobs = jobs.entrySet().stream()
				.filter(entry -> entry.getValue().hasHeartbeat())
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		return runningJobs;
	}

	public Map<IdPair, OfferJob> getScheduledJobs() {
		Map<IdPair, OfferJob> scheduledJobs = jobs.entrySet().stream()
				.filter(entry -> entry.getValue().isScheduled())
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		return scheduledJobs;
	}

//	public Map<IdPair, OfferJob> getNewJobs() {
//		Map<IdPair, OfferJob> newJobs = jobs.entrySet().stream()
//				.filter(entry -> entry.getValue().isNew())
//				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
//		return newJobs;
//	}
	
//	public int getRunningSlots(String userId) {
//		return getSlots(getRunningJobs().values(), userId);
//	}
//	
//	public int getScheduledSlots(String userId) {
//		return getSlots(getScheduledJobs().values(), userId);
//	}
//	
//	public int getNewSlots(String userId) {
//		return getSlots(getNewJobs().values(), userId);
//	}

//	public static int getSlots(Collection<WebSocketJobSchedulingState> jobs) {
//		return jobs.stream()
//				.mapToInt(j -> j.getSlots())
//				.sum();
//	}
//	
//	public int getSlots(Collection<WebSocketJobSchedulingState> jobs, String userId) {
//		return jobs.stream()
//				.filter(j -> userId.equals(j.getUserId()))
//				.mapToInt(j -> j.getSlots())
//				.sum();
//	}

	public void remove(IdPair jobId) {
		jobs.remove(jobId);	
	}

	public OfferJob addScheduledJob(IdPair idPair) {
		OfferJob jobState = new OfferJob();
//		jobState.setScheduleTimestamp();
		jobs.put(idPair, jobState);
		return jobState;
	}
//	
//	public void addRunningJob(IdPair idPair, String userId, int slots) {
//		WebSocketJobSchedulingState job = new WebSocketJobSchedulingState(userId, slots);
//		job.setScheduleTimestamp();
//		job.setRunningTimestamp();
//		jobs.put(idPair, job);
//	}

	public OfferJob get(IdPair jobIdPair) {
		return jobs.get(jobIdPair);
	}

	public boolean containsJobId(UUID jobId) {
		return jobs.keySet().stream()
				.map(p -> p.getJobId())
				.anyMatch(id -> jobId.equals(id));
	}
}
