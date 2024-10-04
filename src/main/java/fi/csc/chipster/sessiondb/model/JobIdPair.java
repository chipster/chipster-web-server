package fi.csc.chipster.sessiondb.model;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;

public class JobIdPair implements Serializable {

	@Column(columnDefinition = "uuid", updatable = false) // uuid instead of binary
	private UUID sessionId;
	@Column(columnDefinition = "uuid", updatable = false) // uuid instead of binary
	private UUID jobId;

	public JobIdPair() {
		// needed by JSON (de)serialization
	}

	public JobIdPair(UUID sessionId, UUID jobId) {
		this.sessionId = sessionId;
		this.jobId = jobId;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public UUID getJobId() {
		return jobId;
	}

	/*
	 * generated by Eclipse
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((jobId == null) ? 0 : jobId.hashCode());
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
		JobIdPair other = (JobIdPair) obj;
		if (jobId == null) {
			if (other.jobId != null)
				return false;
		} else if (!jobId.equals(other.jobId))
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
		return jobId.toString().substring(0, 4);
	}
}