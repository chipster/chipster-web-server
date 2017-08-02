package fi.csc.chipster.sessiondb.model;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.Lob;
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
	
	// mappedBy="session" means that this relation is owned by the other side
	@OneToMany(mappedBy="session")
	private Collection<Dataset> datasets;
	
	@OneToMany(mappedBy="session")
	private Collection<Job> jobs;
	
	@OneToMany(fetch = FetchType.EAGER, mappedBy="session")
	private Collection<Authorization> authorizations;
	
	/**
	 * All jobs in the session
	 * 
	 * Changes to the database has to be made with Job.setSession().
	 * 
	 * @return
	 */
	// not needed in session JSON, because there is a separate endpoint for this
	@XmlTransient // rest
	public Map<UUID, Job> getJobs() {
		HashMap<UUID, Job> map = new HashMap<>();
		for (Job job : jobs) {
			map.put(job.getJobId(), job);
		}
		return map;
	}
	
	/**
	 * Set jobs to this instance.
	 * 
	 * Changes to the database has to be made with Job.setSession().
	 * 
	 * @param jobs
	 */
	public void setJobs(Map<UUID, Job> jobs) {
		this.jobs = jobs.values();
	}

	/**
	 * All dataset in the session
	 * 
	 * Changes to the database has to made with Dataset.setSession().
	 * 
	 * @return
	 */
	// not needed in session JSON, because there is a separate endpoint for this
	@XmlTransient // rest
	public Map<UUID, Dataset> getDatasets() {
		HashMap<UUID, Dataset> map = new HashMap<>();
		for (Dataset dataset : datasets) {
			map.put(dataset.getDatasetId(), dataset);
		}
		return map;
	}
	
	@XmlTransient 
	public Collection<Dataset> getDatasetCollection() {
		return datasets;
	}

	/**
	 * Set dataset to this instance
	 * 
	 * Changes to the database has to be made with Dataset.setSession().
	 * 
	 * @param datasets
	 */
	public void setDatasets(Map<UUID, Dataset> datasets) {
		this.datasets = datasets.values();
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

	public Collection<Authorization> getAuthorizations() {
		return authorizations;
	}

	public void setAuthorizations(Collection<Authorization> authorizations) {
		this.authorizations = authorizations;
	}
}
