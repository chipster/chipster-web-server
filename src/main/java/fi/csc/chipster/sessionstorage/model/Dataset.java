package fi.csc.chipster.sessionstorage.model;

import java.util.Date;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.xml.bind.annotation.XmlRootElement;

@Entity // db
@XmlRootElement // rest
public class Dataset {

	@Id // db
	private String id;
	private String name;
	private long size;
	private String checksum;
	private Integer x;
	private Integer y;
	private Date created;
	private Date accessed;
	private String sourceJob;
	
	public Dataset() {} // JAXB needs this

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

	public long getSize() {
		return size;
	}

	public void setSize(long size) {
		this.size = size;
	}

	public String getChecksum() {
		return checksum;
	}

	public void setChecksum(String checksum) {
		this.checksum = checksum;
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

	public String getSourceJob() {
		return sourceJob;
	}

	public void setSourceJob(String sourceJob) {
		this.sourceJob = sourceJob;
	}
}
