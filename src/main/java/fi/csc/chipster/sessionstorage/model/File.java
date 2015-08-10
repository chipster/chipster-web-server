package fi.csc.chipster.sessionstorage.model;

import javax.persistence.Entity;
import javax.persistence.Id;

@Entity // db
public class File {

	@Id // db
	private String fileId;
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

	public String getFileId() {
		return fileId;
	}

	public void setFileId(String fileId) {
		this.fileId = fileId;
	}
}
