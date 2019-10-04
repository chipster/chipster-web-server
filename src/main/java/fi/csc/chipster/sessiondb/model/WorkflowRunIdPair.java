package fi.csc.chipster.sessiondb.model;

import java.io.Serializable;
import java.util.UUID;

import javax.persistence.Column;

public class WorkflowRunIdPair implements Serializable {

	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID sessionId;
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID workflowRunId;
	
	public WorkflowRunIdPair() {
		// needed by JSON (de)serialization
	}

	public WorkflowRunIdPair(UUID sessionId, UUID workflowRunId) {
		this.sessionId = sessionId;
		this.workflowRunId = workflowRunId;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public UUID getWorkflowRunId() {
		return workflowRunId;
	}

	/* 
	 * generated by Eclipse
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((workflowRunId == null) ? 0 : workflowRunId.hashCode());
		result = prime * result + ((sessionId == null) ? 0 : sessionId.hashCode());
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
		WorkflowRunIdPair other = (WorkflowRunIdPair) obj;
		if (workflowRunId == null) {
			if (other.workflowRunId != null)
				return false;
		} else if (!workflowRunId.equals(other.workflowRunId))
			return false;
		if (sessionId == null) {
			if (other.sessionId != null)
				return false;
		} else if (!sessionId.equals(other.sessionId))
			return false;
		return true;
	}
	
	@Override
	public String toString() {
		return workflowRunId.toString().substring(0, 4);
	}
}