package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(indexes = {
        @Index(columnList = "username", name = "rule_username_index"),
        @Index(columnList = "sessionId", name = "rule_sessionid_index"),
        @Index(columnList = "sharedBy", name = "rule_sharedby_index"),
})
public class Rule {
	
	@Id // db
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID ruleId;	 
	private String username;
	
	@ManyToOne(fetch=FetchType.EAGER)
	@JoinColumn(name="sessionId")
	private Session session;
	
	private boolean readWrite;
	private String sharedBy;
	private Instant created;
	
	public Rule() { } // hibernate needs this			
	
	public Rule(Rule o) {
		this.ruleId = o.ruleId;
		this.username = o.username;
		this.session = o.session;
		this.readWrite = o.readWrite;
		this.sharedBy = o.sharedBy;
		this.created = o.created;
	}
	
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
	
	@JsonIgnore
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

	public Instant getCreated() {
		return created;
	}

	public void setCreated(Instant created) {
		this.created = created;
	}	
}
