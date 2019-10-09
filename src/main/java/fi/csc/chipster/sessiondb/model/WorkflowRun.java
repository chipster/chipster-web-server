package fi.csc.chipster.sessiondb.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import fi.csc.microarray.messaging.JobState;

@Entity // db
@XmlRootElement // rest
@Table(indexes = { @Index(columnList = "sessionId", name = "workflowrun_sessionid_index"), })
public class WorkflowRun {

	@EmbeddedId // db
	@JsonUnwrapped
	private WorkflowRunIdPair workflowRunIdPair;

	private String workflowPlanId;
	private String currentWorkflowJobPlanId;
	private String currentJobPlanId;
	private JobState state;
	private Instant created;
	private Instant endTime;
	private String createdBy;
	private String name;
	
	public UUID getWorkflowRunId() {
		if (this.workflowRunIdPair == null) {
			return null;
		}
		return this.workflowRunIdPair.getWorkflowRunId();
	}
	
	public String getWorkflowPlanId() {
		return workflowPlanId;
	}
	public void setWorkflowPlanId(String workflowPlanId) {
		this.workflowPlanId = workflowPlanId;
	}
	public String getCurrentWorkflowJobPlanId() {
		return currentWorkflowJobPlanId;
	}
	public void setCurrentWorkflowJobPlanId(String currentWorkflowJobPlanId) {
		this.currentWorkflowJobPlanId = currentWorkflowJobPlanId;
	}
	public String getCurrentJobPlanId() {
		return currentJobPlanId;
	}
	public void setCurrentJobPlanId(String currentJobPlanId) {
		this.currentJobPlanId = currentJobPlanId;
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

	public Serializable getWorkflowRunIdPair() {
		return this.workflowRunIdPair;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
}
