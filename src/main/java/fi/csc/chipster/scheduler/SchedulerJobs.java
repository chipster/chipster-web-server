package fi.csc.chipster.scheduler;

import java.util.HashMap;
import java.util.Map;
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

	public void remove(IdPair jobId) {
		jobs.remove(jobId);	
	}

	public void addNewJob(IdPair idPair) {
		jobs.put(idPair, new JobSchedulingState());
	}
	
	public void addRunningJob(IdPair idPair) {
		JobSchedulingState job = new JobSchedulingState();
		job.setScheduleTimestamp();
		job.setRunningTimestamp();
		jobs.put(idPair, new JobSchedulingState());
	}

	public JobSchedulingState get(IdPair jobIdPair) {
		return jobs.get(jobIdPair);
	}
}
