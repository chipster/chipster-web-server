package fi.csc.chipster.sessionstorage.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.xml.bind.annotation.XmlRootElement;
@Entity // db
@XmlRootElement // REST
public class Session {

	@Id // db
	private String id;
	private String name;
	private String owner;
	private String notes;

//    @ElementCollection(targetClass=String.class, fetch=FetchType.EAGER)
//    @Column(name="job")
//    private List<String> jobs = new ArrayList<>();
	
	public Session() {} // JAXB needs this
	
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
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

//	public List<Dataset> getJobs() {
//		return jobs;
//	}
//
//	public void setJobs(List<Dataset> jobs) {
//		this.jobs = jobs;
//	}

}
