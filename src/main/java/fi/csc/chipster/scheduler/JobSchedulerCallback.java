package fi.csc.chipster.scheduler;

public interface JobSchedulerCallback {

	void newResourcesAvailable();

	void expire(IdPair jobIdPair, String string);
}
