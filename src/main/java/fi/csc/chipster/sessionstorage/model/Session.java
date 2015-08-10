package fi.csc.chipster.sessionstorage.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
	private Date created;
	private Date accessed;
	
	/* - cascade updates so that adding an object to the collection
	 * persists also the object itself 
	 */
//	@ManyToMany(cascade=CascadeType.ALL)
//	// rename Datasets_id to Dataset_id
//	@JoinTable(inverseJoinColumns=@JoinColumn(name="Dataset_id"))
	@OneToMany(cascade=CascadeType.ALL)
	@JoinColumn(name="sessionId")
	private List<Dataset> datasets = new ArrayList<>();
	
//	@ManyToMany(cascade=CascadeType.ALL)
//	// rename Jobs_id to Job_id
//	@JoinTable(inverseJoinColumns=@JoinColumn(name="Job_id"))
	@OneToMany(cascade=CascadeType.ALL)
	@JoinColumn(name="sessionId")
	private List<Job> jobs = new ArrayList<>();
	
	// not needed in session JSON, because there is a separate endpoint for this
	@XmlTransient // rest
	public List<Job> getJobs() {
		return jobs;
	}

	public void setJobs(List<Job> jobs) {
		this.jobs = jobs;
	}

	// not needed in session JSON, because there is a separate endpoint for this
	@XmlTransient // rest
	public List<Dataset> getDatasets() {
		return datasets;
	}

	public void setDatasets(List<Dataset> datasets) {
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

	public Date getCreated() {
		return created;
	}

	public void setCreated(Date created) {
		this.created = created;
	}

	public Date getAccessed() {
		return accessed;
	}

	public void setAccessed(Date accessed) {
		this.accessed = accessed;
	}
}
