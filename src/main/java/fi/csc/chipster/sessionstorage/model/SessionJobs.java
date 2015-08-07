package fi.csc.chipster.sessionstorage.model;

import java.util.ArrayList;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.Table;

/**
 *  
 * 
 * @author klemela
 */
@Entity // db
@Table(name = "Session")
public class SessionJobs {

	@Id // db
	@Column(name = "id")
	private String sessionId;
	
	/* - cascade updates so that adding a job to the jobs collection
	 * persists also the job itself
	 * - load collections eagerly to make them available for json conversion 
	 * after the transaction is closed
	 */
	@ManyToMany(cascade=CascadeType.ALL, fetch = FetchType.EAGER)
	// rename Jobs_id to Job_id
	@JoinTable(inverseJoinColumns=@JoinColumn(name="Job_id"))
	private List<Job> jobs = new ArrayList<>();
	
	public SessionJobs() {} // JAXB needs this
	

	public List<Job> getJobs() {
		return jobs;
	}

	public void setJobs(List<Job> jobs) {
		this.jobs = jobs;
	}

	public String getSessionId() {
		return sessionId;
	}


	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}
}
