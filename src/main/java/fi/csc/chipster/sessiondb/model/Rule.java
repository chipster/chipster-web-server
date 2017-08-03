package fi.csc.chipster.sessiondb.model;

import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.xml.bind.annotation.XmlTransient;

@Entity
public class Rule {
	
	@Id // db
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID ruleId;	 
	private String username;
	
	@XmlTransient
	@ManyToOne
	@JoinColumn(name="sessionId")	
	private Session session;
	
	private boolean readWrite;
	private String sharedBy;
	
	public Rule() { } // hibernate needs this			
	
	public Rule(String username, boolean readWrite) {
		this(username, readWrite, null);
	}
	
	public Rule(String username, boolean readWrite, String authorizedBy) {
		this.username = username;
		this.readWrite = readWrite;
		this.sharedBy = authorizedBy;
	}
	public String getUsername() {
		return username;
	}
	public void setUsername(String username) {
		this.username = username;
	}
	
	public UUID getRuleId() {
		return ruleId;
	}
	public void setRuleId(UUID authorizationId) {
		this.ruleId = authorizationId;
	}
	
	@XmlTransient
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

	public String getSharedBy() {
		return sharedBy;
	}

	public void setSharedBy(String sharedBy) {
		this.sharedBy = sharedBy;
	}	
}
