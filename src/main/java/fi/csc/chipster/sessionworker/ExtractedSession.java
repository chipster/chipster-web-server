package fi.csc.chipster.sessionworker;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Label;
import fi.csc.chipster.sessiondb.model.Session;

public class ExtractedSession {

	private Session session;
	private Map<UUID, Dataset> datasetMap;
	private Map<UUID, Job> jobMap;
	private Map<UUID, Label> labelMap;
	private ArrayList<String> warnings;
	private ArrayList<String> errors;

	public ExtractedSession(Session session, Map<UUID, Dataset> datasetMap, Map<UUID, Job> jobMap,
			Map<UUID, Label> labelMap, ArrayList<String> warnings, ArrayList<String> errors) {
		this.setSession(session);
		this.datasetMap = datasetMap;
		this.jobMap = jobMap;
		this.labelMap = labelMap;
		this.setWarnings(warnings);
		this.setErrors(errors);
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

	public Map<UUID, Label> getLabelMap() {
		return labelMap;
	}

	public void setLabelMap(Map<UUID, Label> labelMap) {
		this.labelMap = labelMap;
	}

	public ArrayList<String> getWarnings() {
		return warnings;
	}

	public void setWarnings(ArrayList<String> warnings) {
		this.warnings = warnings;
	}

	public ArrayList<String> getErrors() {
		return errors;
	}

	public void setErrors(ArrayList<String> errors) {
		this.errors = errors;
	}
}
