package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import org.hibernate.annotations.Type;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import fi.csc.chipster.sessiondb.FileUtils;
import jakarta.xml.bind.annotation.XmlRootElement;

@Entity // db
@XmlRootElement // rest
@Table(indexes = { @Index(columnList = "fileId", name = "dataset_fileid_index"),
		@Index(columnList = "sessionId", name = "dataset_sessionid_index"), })
public class Dataset {

	@EmbeddedId // db
	@JsonUnwrapped
	private DatasetIdPair datasetIdPair;
	private String name;
	@Lob
	private String notes;

	@Column
	@Type(type = MetadataFile.METADATA_FILE_LIST_JSON_TYPE)
	private List<MetadataFile> metadataFiles = new ArrayList<>();

	private Integer x;
	private Integer y;
	private UUID sourceJob;
	private Instant created;

	@ManyToOne
	@JoinColumn(name = "fileId")
	// embed fields of the File object directly
	@JsonUnwrapped // rest
	private File file;

	public Dataset() {
	} // JAXB needs this

	public UUID getDatasetId() {
		if (datasetIdPair == null) {
			return null;
		}
		return datasetIdPair.getDatasetId();
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
		if (!FileUtils.isEmpty(file)) {
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

	public UUID getSessionId() {
		if (datasetIdPair == null) {
			return null;
		}
		return this.datasetIdPair.getSessionId();
	}

	public Instant getCreated() {
		return created;
	}

	public void setCreated(Instant created) {
		this.created = created;
	}

	public void setDatasetIdPair(DatasetIdPair datasetIdPair) {
		this.datasetIdPair = datasetIdPair;
	}

	public void setDatasetIdPair(UUID sessionId, UUID datasetId) {
		setDatasetIdPair(new DatasetIdPair(sessionId, datasetId));
	}

	public DatasetIdPair getDatasetIdPair() {
		return datasetIdPair;
	}

	public List<MetadataFile> getMetadataFiles() {
		return metadataFiles;
	}

	public void setMetadataFiles(List<MetadataFile> metadataFiles) {
		this.metadataFiles = metadataFiles;
	}

}
