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
public class SessionDatasets {

	@Id // db
	@Column(name = "id")
	private String sessionId;
	
	/* - cascade updates so that adding a dataset to the datasets collection
	 * persists also the dataset itself
	 * - load collections eagerly to make them available for json conversion 
	 * after the transaction is closed
	 */
	@ManyToMany(cascade=CascadeType.ALL, fetch = FetchType.EAGER)
	// rename Datasets_id to Dataset_id
	@JoinTable(inverseJoinColumns=@JoinColumn(name="Dataset_id"))
	private List<Dataset> datasets = new ArrayList<>();
	
	public SessionDatasets() {} // JAXB needs this
	

	public List<Dataset> getDatasets() {
		return datasets;
	}

	public void setDatasets(List<Dataset> datasets) {
		this.datasets = datasets;
	}

	public String getSessionId() {
		return sessionId;
	}


	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}
}
