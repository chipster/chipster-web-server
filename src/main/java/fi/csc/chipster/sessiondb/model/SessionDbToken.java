package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement // json
public class SessionDbToken {
	
	public enum Access { READ_ONLY, READ_WRITE }
	
	private String tokenKey;
	private Instant valid;	
	private String username;
	private UUID sessionId;
	private UUID datasetId;
	private Access access;		
	
	public SessionDbToken() { /* for JSON */ }
	
	public SessionDbToken(String tokenKey, String username, UUID sessionId, UUID datasetId, Instant valid, Access access) {
		this.tokenKey = tokenKey;
		this.username = username;
		this.sessionId = sessionId;
		this.datasetId = datasetId;
		this.valid = valid;
		this.access = access;
	}
	
	public String getUsername() {
		return username;
	}
	
	public void setUsername(String username) {
		this.username = username;
	}

	public Instant getValid() {
		return valid;
	}

	public void setValid(Instant valid) {
		this.valid = valid;
	}

	public String getTokenKey() {
		return tokenKey;
	}

	public void setTokenKey(String tokenKey) {
		this.tokenKey = tokenKey;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public void setSessionId(UUID sessionId) {
		this.sessionId = sessionId;
	}

	public UUID getDatasetId() {
		return datasetId;
	}

	public void setDatasetId(UUID datasetId) {
		this.datasetId = datasetId;
	}

	public Access getAccess() {
		return access;
	}

	public void setAccess(Access access) {
		this.access = access;
	}	
}
