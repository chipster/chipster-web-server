package fi.csc.chipster.scheduler;

public interface JobSchedulerCallback {

	void newResourcesAvailable(JobScheduler jobScheduler);

	void expire(IdPair jobIdPair, String string, String screenOutput);

	void busy(IdPair idPair);
}
