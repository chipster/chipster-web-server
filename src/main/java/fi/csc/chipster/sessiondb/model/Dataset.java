package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.xml.bind.annotation.XmlRootElement;

import org.hibernate.annotations.Type;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

@Entity // db
@XmlRootElement // rest
public class Dataset {
	
	@Id // db
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID datasetId;
	private String name;
	@Lob
	private String notes;
	private Integer x;
	private Integer y;
	private UUID sourceJob;
	private Instant created;
	
	@ManyToOne
	@JoinColumn(name="sessionId")
	private Session session;
	
	// handle delete manually only when the file is orphan
	@ManyToOne(cascade={CascadeType.DETACH, CascadeType.MERGE, CascadeType.PERSIST, CascadeType.REFRESH})
	@JoinColumn(name="fileId")
	// embed fields of the File object directly 
	@JsonUnwrapped // rest
	private File file;
	
	@Column
	@Type(type = MetadataEntry.METADATA_ENTRY_LIST_JSON_TYPE)
	private List<MetadataEntry> metadata = new ArrayList<>();
	
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
		// jackson creates an empty object even when the client didn't set it
		if (file != null && !file.isEmpty()) {
			this.file = file;
		} else {
			this.file = null;
		}
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	public List<MetadataEntry> getMetadata() {
		return metadata;
	}

	public void setMetadata(List<MetadataEntry> metadata) {
		this.metadata = metadata;
	}

	@JsonIgnore
	public Session getSession() {
		return session;
	}

	public void setSession(Session session) {
		this.session = session;
	}

	public Instant getCreated() {
		return created;
	}

	public void setCreated(Instant created) {
		this.created = created;
	}
}
