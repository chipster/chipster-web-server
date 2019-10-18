package fi.csc.chipster.sessiondb.model;

import java.time.Duration;
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

@Entity // db
@XmlRootElement // rest
@Table(indexes = { @Index(columnList = "sessionId", name = "workflowplan_sessionid_index"), })
public class WorkflowPlan {

	@EmbeddedId // db
	@JsonUnwrapped
	private WorkflowPlanIdPair workflowPlanIdPair;

	private String name;
	private Instant created;
	private Duration originalDuration;
	@Lob
	private String notes;
	
	@Column
	@Type(type = WorkflowJob.WORKFLOW_JOB_LIST_JSON_TYPE)
	private List<WorkflowJob> workflowJobs = new ArrayList<>();

	public UUID getWorkflowPlanId() {
		if (workflowPlanIdPair == null) {
			return null;
		}
		return this.workflowPlanIdPair.getWorkflowPlanId();
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Instant getCreated() {
		return created;
	}

	public void setCreated(Instant created) {
		this.created = created;
	}

	public List<WorkflowJob> getWorkflowJobs() {
		return workflowJobs;
	}

	public void setWorkflowJobs(List<WorkflowJob> jobs) {
		this.workflowJobs = jobs;
	}

	public Duration getOriginalDuration() {
		return originalDuration;
	}

	public void setOriginalDuration(Duration originalDuration) {
		this.originalDuration = originalDuration;
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	public Object getSessionId() {
		if (this.workflowPlanIdPair == null) {
			return null;
		}
		return this.workflowPlanIdPair.getSessionId();
	}

	public void setWorkflowPlanIdPair(WorkflowPlanIdPair idPair) {
		this.workflowPlanIdPair = idPair;
	}

	public void setWorkflowPlanIdPair(UUID sessionId, UUID planId) {
		this.setWorkflowPlanIdPair(new WorkflowPlanIdPair(sessionId, planId));
	}

	public WorkflowPlanIdPair getWorkflowPlanIdPair() {
		return this.workflowPlanIdPair;
	}
}
