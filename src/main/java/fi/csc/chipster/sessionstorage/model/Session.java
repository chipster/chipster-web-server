package fi.csc.chipster.sessionstorage.model;

import java.time.LocalDateTime;
import java.util.Map;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToMany;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
@Entity // db
@XmlRootElement // REST
public class Session {

	public Session() {} // JAXB needs this
	
	@Id // db
	private String sessionId;
	private String name;
	private String owner;
	private String notes;
	private LocalDateTime created;
	private LocalDateTime accessed;
	
	/* - cascade updates so that adding an object to the collection
	 * persists also the object itself 
	 */
	@OneToMany(cascade=CascadeType.ALL)
	@JoinColumn(name="sessionId")
	private Map<String, Dataset> datasets;
	
	@OneToMany(cascade=CascadeType.ALL)
	@JoinColumn(name="sessionId")
	private Map<String, Job> jobs;
	
	// not needed in session JSON, because there is a separate endpoint for this
	@XmlTransient // rest
	public Map<String, Job> getJobs() {
		return jobs;
	}

	public void setJobs(Map<String, Job> jobs) {
		this.jobs = jobs;
	}

	// not needed in session JSON, because there is a separate endpoint for this
	@XmlTransient // rest
	public Map<String, Dataset> getDatasets() {
		return datasets;
	}

	public void setDatasets(Map<String, Dataset> datasets) {
		this.datasets = datasets;
	}
	
	public String getSessionId() {
		return sessionId;
	}

	public void setSessionId(String id) {
		this.sessionId = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getOwner() {
		return owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
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
