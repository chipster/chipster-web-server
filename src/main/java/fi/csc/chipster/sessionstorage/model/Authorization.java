package fi.csc.chipster.sessionstorage.model;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.OneToOne;

@Entity
public class Authorization {
	
	@Id 
	@GeneratedValue
	private int authorizationId;
	
	private String username;
//	@ManyToOne(cascade=CascadeType.ALL)
//	@JoinColumn(name="sessionId")
	@OneToOne(cascade=CascadeType.ALL)
	private Session session;
	
	private boolean readWrite;
	
	public Authorization() { } // hibernate needs this			
	
	public Authorization(String username, Session session, boolean readWrite) {
		this.username = username;
		this.session = session;
		this.readWrite = readWrite;
	}
	public String getUsername() {
		return username;
	}
	public void setUsername(String username) {
		this.username = username;
	}
	public int getAuthorizationId() {
		return authorizationId;
	}
	public void setAuthorizationId(int authorizationId) {
		this.authorizationId = authorizationId;
	}
	public Session getSession() {
		return session;
	}
	public void setSession(Session session) {
		this.session = session;
	}

	public boolean isReadWrite() {
		return readWrite;
	}

	public void setReadWrite(boolean readWrite) {
		this.readWrite = readWrite;
	}	
}
