package fi.csc.chipster.sessiondb.model;

import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

@Entity
public class Authorization {
	
	@Id // db
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID authorizationId;	 
	private String username;
	
	@ManyToOne
	@JoinColumn(name="sessionId")	
	private Session session;
	
	private boolean readWrite;
	private String authorizedBy;
	
	public Authorization() { } // hibernate needs this			
	
	public Authorization(String username, Session session, boolean readWrite) {
		this(username, session, readWrite, null);
	}
	
	public Authorization(String username, Session session, boolean readWrite, String authorizedBy) {
		this.username = username;
		this.session = session;
		this.readWrite = readWrite;
		this.authorizedBy = authorizedBy;
	}
	public String getUsername() {
		return username;
	}
	public void setUsername(String username) {
		this.username = username;
	}
	
	public UUID getAuthorizationId() {
		return authorizationId;
	}
	public void setAuthorizationId(UUID authorizationId) {
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

	public String getAuthorizedBy() {
		return authorizedBy;
	}

	public void setAuthorizedBy(String authorizedBy) {
		this.authorizedBy = authorizedBy;
	}	
}
