package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.xml.bind.annotation.XmlRootElement;

import org.hibernate.annotations.Type;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import fi.csc.microarray.messaging.JobState;

@Entity // db
@XmlRootElement // rest
@Table(indexes = { @Index(columnList = "sessionId", name = "workflowrun_sessionid_index"), })
public class WorkflowRun {

	@EmbeddedId // db
	@JsonUnwrapped
	private WorkflowRunIdPair workflowRunIdPair;

	private JobState state;
	@Lob
	private String stateDetail;
	private Instant created;
	private Instant endTime;
	private String createdBy;
	private String name;
	
	@Column
	@Type(type = WorkflowJob.WORKFLOW_JOB_LIST_JSON_TYPE)
	private List<WorkflowJob> workflowJobs = new ArrayList<>();
	
	public UUID getWorkflowRunId() {
		if (this.workflowRunIdPair == null) {
			return null;
		}
		return this.workflowRunIdPair.getWorkflowRunId();
	}
	
	public JobState getState() {
		return state;
	}
	public void setState(JobState state) {
		this.state = state;
	}
	public Instant getCreated() {
		return created;
	}
	public void setCreated(Instant created) {
		this.created = created;
	}
	public Instant getEndTime() {
		return endTime;
	}
	public void setEndTime(Instant endTime) {
		this.endTime = endTime;
	}
	public String getCreatedBy() {
		return createdBy;
	}
	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}
	public UUID getSessionId() {
		if (this.workflowRunIdPair == null) {
			return null;
		}
		return this.workflowRunIdPair.getSessionId();
	}

	public void setWorkflowRunIdPair(WorkflowRunIdPair pair) {
		this.workflowRunIdPair = pair;
	}

	public void setWorkflowRunIdPair(UUID sessionId, UUID runId) {
		this.setWorkflowRunIdPair(new WorkflowRunIdPair(sessionId, runId));
	}

	public WorkflowRunIdPair getWorkflowRunIdPair() {
		return this.workflowRunIdPair;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<WorkflowJob> getWorkflowJobs() {
		return workflowJobs;
	}

	public void setWorkflowJobs(List<WorkflowJob> workflowJobs) {
		this.workflowJobs = workflowJobs;
	}

	public String getStateDetail() {
		return stateDetail;
	}

	public void setStateDetail(String stateDetail) {
		this.stateDetail = stateDetail;
	}
}
