package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity // db
public class File implements Cloneable {

	@Id // db
	@Column(columnDefinition = "uuid", updatable = false) // uuid instead of binary
	private UUID fileId;
	private long size = -1;
	private String checksum;
	private Instant fileCreated;
	private String storage;
	private FileState state;
	private String encryptionKey;

	public String getEncryptionKey() {
		return encryptionKey;
	}

	public void setEncryptionKey(String encryptionKey) {
		this.encryptionKey = encryptionKey;
	}

	public FileState getState() {
		return state;
	}

	public void setState(FileState state) {
		this.state = state;
	}

	public File() {
	} // JAXB needs this

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

	public UUID getFileId() {
		return fileId;
	}

	public void setFileId(UUID fileId) {
		this.fileId = fileId;
	}

	public Instant getFileCreated() {
		return fileCreated;
	}

	public void setFileCreated(Instant created) {
		this.fileCreated = created;
	}

	public String getStorage() {
		return storage;
	}

	public void setStorage(String storage) {
		this.storage = storage;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		File other = (File) obj;
		return Objects.equals(fileId, other.fileId) && size == other.size && Objects.equals(checksum, other.checksum)
				&& Objects.equals(fileCreated, other.fileCreated) && Objects.equals(storage, other.storage)
				&& state == other.state;
	}

	@Override
	public Object clone() throws CloneNotSupportedException {

		// fine for primitive an immutable fields
		return super.clone();
	}
}
