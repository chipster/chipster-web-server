package fi.csc.chipster.sessiondb.model;

import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;

@Entity // db
public class File {

	@Id // db
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID fileId;
	private long size;
	private String checksum;
	
	public File() {} // JAXB needs this	

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

	@Override
	public boolean equals(Object obj) {
		
		// genereted by eclipse
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		File other = (File) obj;
		if (checksum == null) {
			if (other.checksum != null) {
				return false;
			}
		} else if (!checksum.equals(other.checksum)) {
			return false;
		}
		if (fileId == null) {
			if (other.fileId != null) {
				return false;
			}
		} else if (!fileId.equals(other.fileId)) {
			return false;
		}
		return size == other.size;
	}
}
