package fi.csc.chipster.sessionstorage.model;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

@Entity // db
@XmlRootElement // rest
public class Dataset {

	@Id // db
	private String datasetId;
	private String name;
	private Integer x;
	private Integer y;
	private String sourceJob;
	
	@ManyToOne(cascade=CascadeType.ALL)
	@JoinColumn(name="fileId")
	// embed fields of the File object directly 
	@JsonUnwrapped // rest
	private File file;
	
	public Dataset() {} // JAXB needs this

	public String getDatasetId() {
		return datasetId;
	}

	public void setDatasetId(String id) {
		this.datasetId = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Integer getX() {
		return x;
	}

	public void setX(Integer x) {
		this.x = x;
	}

	public Integer getY() {
		return y;
	}

	public void setY(Integer y) {
		this.y = y;
	}

	public String getSourceJob() {
		return sourceJob;
	}

	public void setSourceJob(String sourceJob) {
		this.sourceJob = sourceJob;
	}

	public File getFile() {
		return file;
	}

	public void setFile(File file) {
		this.file = file;
	}
}
