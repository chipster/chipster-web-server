package fi.csc.chipster.sessiondb.model;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.MapKey;
import javax.persistence.OneToMany;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
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
	private LocalDateTime created;
	private LocalDateTime accessed;
	
	/* - cascade updates so that adding an object to the collection
	 * persists also the object itself  
	 */
	@OneToMany(cascade=CascadeType.ALL)
	@JoinColumn(name="sessionId") // relationship stored in Dataset table
	@MapKey // Dataset IDs as keys
	private Map<UUID, Dataset> datasets;
	
	@OneToMany(cascade=CascadeType.ALL)
	@JoinColumn(name="sessionId")
	@MapKey
	private Map<UUID, Job> jobs;
	
	// not needed in session JSON, because there is a separate endpoint for this
	@XmlTransient // rest
	public Map<UUID, Job> getJobs() {
		return jobs;
	}

	public void setJobs(Map<UUID, Job> jobs) {
		this.jobs = jobs;
	}

	// not needed in session JSON, because there is a separate endpoint for this
	@XmlTransient // rest
	public Map<UUID, Dataset> getDatasets() {
		return datasets;
	}

	public void setDatasets(Map<UUID, Dataset> datasets) {
		this.datasets = datasets;
	}
	
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

	public LocalDateTime getCreated() {
		return created;
	}

	public void setCreated(LocalDateTime created) {
		this.created = created;
	}

	public LocalDateTime getAccessed() {
		return accessed;
	}

	public void setAccessed(LocalDateTime accessed) {
		this.accessed = accessed;
	}
}
