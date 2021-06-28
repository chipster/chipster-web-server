package fi.csc.chipster.scheduler;

public interface JobSchedulerCallback {

	void newResourcesAvailable(JobScheduler jobScheduler);

	void expire(IdPair jobIdPair, String string);

	void busy(IdPair idPair);
}
