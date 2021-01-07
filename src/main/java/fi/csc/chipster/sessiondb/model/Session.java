package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.OneToMany;
import jakarta.xml.bind.annotation.XmlRootElement;

@Entity // db
@XmlRootElement // REST
public class Session {

	public Session() {} // JAXB needs this
	
	@Id // db
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID sessionId;
	private String name;
	@Lob
	private String notes;
	private Instant created;
	private Instant accessed;
	private SessionState state;
	
	@OneToMany(fetch = FetchType.LAZY, mappedBy="session")
	private Set<Rule> rules;
	
	
	public UUID getSessionId() {
		return this.sessionId;
	}

	public void setSessionId(UUID id) {
		this.sessionId = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	public Instant getCreated() {
		return created;
	}

	public void setCreated(Instant created) {
		this.created = created;
	}

	public Instant getAccessed() {
		return accessed;
	}

	public void setAccessed(Instant accessed) {
		this.accessed = accessed;
	}

	public Set<Rule> getRules() {
		return rules;
	}

	public void setRules(Set<Rule> rules) {
		this.rules = rules;
	}

	public SessionState getState() {
		return state;
	}

	public void setState(SessionState state) {
		this.state = state;
	}
	
}
