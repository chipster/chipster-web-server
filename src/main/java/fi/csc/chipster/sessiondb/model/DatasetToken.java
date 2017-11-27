package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.UUID;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.OneToOne;
import javax.xml.bind.annotation.XmlRootElement;

@Entity //db
@XmlRootElement // json
public class DatasetToken {
	
	@Id // db
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID tokenKey;
	private Instant valid;
	
	private String username;
	@OneToOne(cascade=CascadeType.ALL)
	private Session session;
	@OneToOne(cascade=CascadeType.ALL)
	private Dataset dataset;
	
	public DatasetToken() { } // hibernate needs this			
	
	public DatasetToken(UUID tokenKey, String username, Session session, Dataset dataset, Instant valid) {
		this.tokenKey = tokenKey;
		this.username = username;
		this.session = session;
		this.dataset = dataset;
		this.valid = valid;
	}
	
	public String getUsername() {
		return username;
	}
	
	public void setUsername(String username) {
		this.username = username;
	}
	
	public Session getSession() {
		return session;
	}
	
	public void setSession(Session session) {
		this.session = session;
	}

	public Dataset getDataset() {
		return dataset;
	}

	public void setDataset(Dataset dataset) {
		this.dataset = dataset;
	}

	public Instant getValid() {
		return valid;
	}

	public void setValid(Instant valid) {
		this.valid = valid;
	}

	public UUID getTokenKey() {
		return tokenKey;
	}

	public void setTokenKey(UUID tokenKey) {
		this.tokenKey = tokenKey;
	}	
}
