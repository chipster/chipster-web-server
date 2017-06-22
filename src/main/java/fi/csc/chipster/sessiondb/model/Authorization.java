package fi.csc.chipster.sessiondb.model;

import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.ManyToOne;

@Entity
public class Authorization {
	
	private UUID authorizationId;
	
	private String username;
	
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
	
	@Id // db
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	public UUID getAuthorizationId() {
		return authorizationId;
	}
	public void setAuthorizationId(UUID authorizationId) {
		this.authorizationId = authorizationId;
	}
	
	/* Only unidirectional mapping, unlike with the Datasets and Jobs
	 * 
	 * It's not clear whether authorizations should be thought to be part of the session: 
	 * on the one hand they are part of the session, because they should be removed when the session
	 * is removed. On the other hand the authorizations dictate the access permissions to the session and
	 * are thus above it.
	 */ 
	@ManyToOne
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
