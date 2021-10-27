package fi.csc.chipster.scheduler;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class SchedulerJobs {
	
	HashMap<IdPair, SchedulerJob> jobs = new HashMap<>();
	
	public Map<IdPair, SchedulerJob> getRunningJobs() {
		Map<IdPair, SchedulerJob> runningJobs = jobs.entrySet().stream()
				.filter(entry -> entry.getValue().isRunning())
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		return runningJobs;
	}
	
	public Map<IdPair, SchedulerJob> getScheduledJobs() {
		Map<IdPair, SchedulerJob> runningJobs = jobs.entrySet().stream()
				.filter(entry -> entry.getValue().isScheduled())
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		return runningJobs;
	}

	public Map<IdPair, SchedulerJob> getNewJobs() {
		Map<IdPair, SchedulerJob> newJobs = jobs.entrySet().stream()
				.filter(entry -> entry.getValue().isNew())
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		return newJobs;
	}
	
	public int getRunningSlots(String userId) {
		return getSlots(getRunningJobs().values(), userId);
	}
	
	public int getScheduledSlots(String userId) {
		return getSlots(getScheduledJobs().values(), userId);
	}
		
	public int getNewSlots(String userId) {
		return getSlots(getNewJobs().values(), userId);
	}

	public static int getSlots(Collection<SchedulerJob> jobs) {
		return jobs.stream()
				.mapToInt(j -> j.getSlots())
				.sum();
	}
	
	public int getSlots(Collection<SchedulerJob> jobs, String userId) {
		return jobs.stream()
				.filter(j -> userId.equals(j.getUserId()))
				.mapToInt(j -> j.getSlots())
				.sum();
	}

	public SchedulerJob remove(IdPair jobId) {
		return jobs.remove(jobId);	
	}

	public SchedulerJob addNewJob(IdPair idPair, String userId, int slots, String image, String toolId) {
		SchedulerJob jobState = new SchedulerJob(userId, slots, image, toolId);
		jobs.put(idPair, jobState);
		return jobState;
	}
	
	public SchedulerJob addRunningJob(IdPair idPair, String userId, int slots, String image, String toolId) {
		SchedulerJob job = new SchedulerJob(userId, slots, image, toolId);
		job.setRunningTimestamp();
		jobs.put(idPair, job);
		return job;
	}

	public SchedulerJob get(IdPair jobIdPair) {
		return jobs.get(jobIdPair);
	}
}
