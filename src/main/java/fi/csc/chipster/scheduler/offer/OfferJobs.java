package fi.csc.chipster.scheduler.offer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import fi.csc.chipster.scheduler.IdPair;

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

	public void remove(IdPair jobId) {
		jobs.remove(jobId);	
	}

	public OfferJob addScheduledJob(IdPair idPair) {
		OfferJob jobState = new OfferJob();
		jobs.put(idPair, jobState);
		return jobState;
	}

	public OfferJob get(IdPair jobIdPair) {
		return jobs.get(jobIdPair);
	}

	public boolean containsJobId(UUID jobId) {
		return jobs.keySet().stream()
				.map(p -> p.getJobId())
				.anyMatch(id -> jobId.equals(id));
	}
}
