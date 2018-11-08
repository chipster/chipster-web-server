package fi.csc.chipster.sessionworker;

import java.util.Map;
import java.util.UUID;

import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Session;

public class ExtractedSession {

	private Session session;
	private Map<UUID, Dataset> datasetMap;
	private Map<UUID, Job> jobMap;

	public ExtractedSession(Session session, Map<UUID, Dataset> datasetMap, Map<UUID, Job> jobMap) {
		this.setSession(session);
		this.datasetMap = datasetMap;
		this.jobMap = jobMap;
	}

	public Session getSession() {
		return session;
	}

	public void setSession(Session session) {
		this.session = session;
	}

	public Map<UUID, Dataset> getDatasetMap() {
		return datasetMap;
	}

	public Map<UUID, Job> getJobMap() {
		return jobMap;
	}

	public void setJobMap(Map<UUID, Job> jobMap) {
		this.jobMap = jobMap;
	}
}
