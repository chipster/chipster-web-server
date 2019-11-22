package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
@Table(indexes = { @Index(columnList = "sessionId", name = "workflowrun_sessionid_index"), })
public class WorkflowRun {

	@EmbeddedId // db
	@JsonUnwrapped
	private WorkflowRunIdPair workflowRunIdPair;

	private WorkflowState state;
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
	
	public WorkflowState getState() {
		return state;
	}
	public void setState(WorkflowState state) {
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

	public WorkflowRun deepCopy() {
		
		WorkflowRun run = new WorkflowRun();
		
		run.workflowRunIdPair = new WorkflowRunIdPair(this.getSessionId(), this.getWorkflowRunId());
		run.state = this.state;
		run.stateDetail = this.stateDetail;
		run.created = this.created;
		run.endTime = this.endTime;
		run.createdBy = this.createdBy;
		run.name = this.name;
		run.workflowJobs = this.workflowJobs.stream()
				.map(j -> (WorkflowJob)j.deepCopy())
				.collect(Collectors.toList());
		
		return run;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((created == null) ? 0 : created.hashCode());
		result = prime * result + ((createdBy == null) ? 0 : createdBy.hashCode());
		result = prime * result + ((endTime == null) ? 0 : endTime.hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((state == null) ? 0 : state.hashCode());
		result = prime * result + ((stateDetail == null) ? 0 : stateDetail.hashCode());
		result = prime * result + ((workflowJobs == null) ? 0 : workflowJobs.hashCode());
		result = prime * result + ((workflowRunIdPair == null) ? 0 : workflowRunIdPair.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		WorkflowRun other = (WorkflowRun) obj;
		if (created == null) {
			if (other.created != null)
				return false;
		} else if (!created.equals(other.created))
			return false;
		if (createdBy == null) {
			if (other.createdBy != null)
				return false;
		} else if (!createdBy.equals(other.createdBy))
			return false;
		if (endTime == null) {
			if (other.endTime != null)
				return false;
		} else if (!endTime.equals(other.endTime))
			return false;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (state != other.state)
			return false;
		if (stateDetail == null) {
			if (other.stateDetail != null)
				return false;
		} else if (!stateDetail.equals(other.stateDetail))
			return false;
		if (workflowJobs == null) {
			if (other.workflowJobs != null)
				return false;
		} else if (!workflowJobs.equals(other.workflowJobs))
			return false;
		if (workflowRunIdPair == null) {
			if (other.workflowRunIdPair != null)
				return false;
		} else if (!workflowRunIdPair.equals(other.workflowRunIdPair))
			return false;
		return true;
	}
}
