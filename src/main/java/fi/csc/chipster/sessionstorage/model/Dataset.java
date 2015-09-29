package fi.csc.chipster.sessionstorage.model;

import java.util.UUID;

import javax.persistence.CascadeType;
import javax.persistence.Column;
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
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID datasetId;
	private String name;
	private Integer x;
	private Integer y;
	private UUID sourceJob;
	
	@ManyToOne(cascade=CascadeType.ALL)
	@JoinColumn(name="fileId")
	// embed fields of the File object directly 
	@JsonUnwrapped // rest
	private File file;
	
	public Dataset() {} // JAXB needs this

	public UUID getDatasetId() {
		return datasetId;
	}

	public void setDatasetId(UUID id) {
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

	public UUID getSourceJob() {
		return sourceJob;
	}

	public void setSourceJob(UUID sourceJob) {
		this.sourceJob = sourceJob;
	}

	public File getFile() {
		return file;
	}

	public void setFile(File file) {
		this.file = file;
	}
}
