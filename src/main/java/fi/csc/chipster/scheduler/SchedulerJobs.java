package fi.csc.chipster.scheduler;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class SchedulerJobs {
	
	HashMap<IdPair, JobSchedulingState> jobs = new HashMap<>();
	
	public Map<IdPair, JobSchedulingState> getRunningJobs() {
		Map<IdPair, JobSchedulingState> runningJobs = jobs.entrySet().stream()
				.filter(entry -> entry.getValue().isRunning())
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		return runningJobs;
	}

	public Map<IdPair, JobSchedulingState> getScheduledJobs() {
		Map<IdPair, JobSchedulingState> scheduledJobs = jobs.entrySet().stream()
				.filter(entry -> entry.getValue().isScheduled())
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
		return scheduledJobs;
	}

	public Map<IdPair, JobSchedulingState> getNewJobs() {
		Map<IdPair, JobSchedulingState> newJobs = jobs.entrySet().stream()
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
		
	public int getSlots(Collection<JobSchedulingState> jobs, String userId) {
		return jobs.stream()
				.filter(j -> userId.equals(j.getUserId()))
				.mapToInt(j -> j.getSlots())
				.sum();
	}

	public void remove(IdPair jobId) {
		jobs.remove(jobId);	
	}

	public JobSchedulingState addNewJob(IdPair idPair, String userId, int slots) {
		JobSchedulingState jobState = new JobSchedulingState(userId, slots);
		jobs.put(idPair, jobState);
		return jobState;
	}
	
	public void addRunningJob(IdPair idPair, String userId, int slots) {
		JobSchedulingState job = new JobSchedulingState(userId, slots);
		job.setScheduleTimestamp();
		job.setRunningTimestamp();
		jobs.put(idPair, job);
	}

	public JobSchedulingState get(IdPair jobIdPair) {
		return jobs.get(jobIdPair);
	}

	public boolean containsJobId(UUID jobId) {
		return jobs.keySet().stream()
				.map(p -> p.getJobId())
				.anyMatch(id -> jobId.equals(id));
	}
}
