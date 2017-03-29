package fi.csc.chipster.sessiondb.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.persistence.CascadeType;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.persistence.MapKeyColumn;
import javax.persistence.OneToMany;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

@Entity // db
@XmlRootElement // rest
public class Dataset {
	
	@Entity
	public static class ColumnMetadata {
		@ElementCollection(fetch=FetchType.EAGER)
		@MapKeyColumn(name="key")
		@Column(name="value")
		@CollectionTable(name="Metadata", joinColumns=@JoinColumn(name="datasetId"))
		private Map<String, String> metadata;	
	}
	
	@Entity
	public static class TypeTagEntity {
		@ElementCollection(fetch=FetchType.EAGER)
		@MapKeyColumn(name="key")
		@Column(name="value")
		@CollectionTable(name="TypeTag", joinColumns=@JoinColumn(name="datasetId"))
		private Map<String, String> typeTags;	
	}

	@Id // db
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID datasetId;
	private String name;
	@Lob
	private String notes;
	private Integer x;
	private Integer y;
	private UUID sourceJob;
	
	@XmlTransient
	@ManyToOne
	@JoinColumn(name="sessionId")
	private Session session;
	
	// handle delete manually only when the file is orphan
	@ManyToOne(cascade={CascadeType.DETACH, CascadeType.MERGE, CascadeType.PERSIST, CascadeType.REFRESH})
	@JoinColumn(name="fileId")
	// embed fields of the File object directly 
	@JsonUnwrapped // rest
	private File file;
	
	@OneToMany(cascade=CascadeType.ALL, fetch=FetchType.EAGER)
	@JoinColumn(name="datasetId")
	private List<MetadataEntry> metadata;
	
	@OneToMany(cascade=CascadeType.ALL, fetch=FetchType.EAGER)
	@JoinColumn(name="datasetId")
	private List<TypeTag> typeTags;
	
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
	
	public List<TypeTag> getTypeTags() {
		return typeTags;
	}
	
	public void setTypeTags(List<TypeTag> typeTags) {
		this.typeTags = typeTags;
	}


	public Session getSession() {
		return session;
	}

	public void setSession(Session session) {
		this.session = session;
	}
}
