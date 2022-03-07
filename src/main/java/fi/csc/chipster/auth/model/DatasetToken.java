package fi.csc.chipster.auth.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * Dataset token allows access to specific dataset for a limited period of time
 * 
 * The app creates dataset tokens to create dataset urls for dataset download and 
 * visualisation. 
 * 
 * Dataset tokens allow only read-only access for now.
 * 
 * 
 * @author klemela
 *
 */
@XmlRootElement // json
public class DatasetToken extends ChipsterToken {
		
	private UUID sessionId;
	private UUID datasetId;		
	
	public DatasetToken() { /* for JSON */ }
	
	public DatasetToken(String username, UUID sessionId, UUID datasetId, Instant valid) {
		
		super(username, valid, Role.DATASET_TOKEN);
		
		this.sessionId = sessionId;
		this.datasetId = datasetId;
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
}
