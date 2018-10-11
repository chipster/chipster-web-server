package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.OneToMany;
import javax.xml.bind.annotation.XmlRootElement;
import com.fasterxml.jackson.annotation.JsonIgnore;

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
	
	// mappedBy="session" means that this relation is owned by the other side
//	@OneToMany(mappedBy="session")
//	private Collection<Dataset> datasets;
	
	@OneToMany(mappedBy="session")
	private Collection<Job> jobs;
	
	@OneToMany(fetch = FetchType.EAGER, mappedBy="session")
	private Set<Rule> rules;
	
	/**
	 * All jobs in the session
	 * 
	 * Changes to the database has to be made with Job.setSession().
	 * 
	 * @return
	 */
	// not needed in session JSON, because there is a separate endpoint for this
	@JsonIgnore
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

//	/**
//	 * All dataset in the session
//	 * 
//	 * Changes to the database has to made with Dataset.setSession().
//	 * 
//	 * @return
//	 */
//	// not needed in session JSON, because there is a separate endpoint for this
//	@JsonIgnore
//	public Map<UUID, Dataset> getDatasets() {
//		HashMap<UUID, Dataset> map = new HashMap<>();
//		for (Dataset dataset : datasets) {
//			map.put(dataset.getDatasetId(), dataset);
//		}
//		return map;
//	}
	
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
	
}
