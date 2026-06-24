package fi.csc.chipster.sessiondb.model;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;

public class LabelIdPair implements Serializable {

	@Column(columnDefinition = "uuid", updatable = false) // uuid instead of binary
	private UUID sessionId;
	@Column(columnDefinition = "uuid", updatable = false) // uuid instead of binary
	private UUID labelId;

	public LabelIdPair() {
		// needed by JSON (de)serialization
	}

	public LabelIdPair(UUID sessionId, UUID labelId) {
		this.sessionId = sessionId;
		this.labelId = labelId;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public UUID getLabelId() {
		return labelId;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((labelId == null) ? 0 : labelId.hashCode());
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
		LabelIdPair other = (LabelIdPair) obj;
		if (labelId == null) {
			if (other.labelId != null)
				return false;
		} else if (!labelId.equals(other.labelId))
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
		return labelId.toString().substring(0, 4);
	}
}
