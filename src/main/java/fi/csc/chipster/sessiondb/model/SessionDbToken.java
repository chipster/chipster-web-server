package fi.csc.chipster.sessiondb.model;

import java.time.Instant;

import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement // json
public class SessionDbToken {
	
	private String tokenKey;
	private Instant valid;	
	private String username;
	private Session session;
	private Dataset dataset;			
	
	public SessionDbToken() { /* for JSON */ }
	
	public SessionDbToken(String tokenKey, String username, Session session, Dataset dataset, Instant valid) {
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

	public String getTokenKey() {
		return tokenKey;
	}

	public void setTokenKey(String tokenKey) {
		this.tokenKey = tokenKey;
	}	
}
