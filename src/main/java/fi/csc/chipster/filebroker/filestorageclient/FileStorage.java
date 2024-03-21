package fi.csc.chipster.filebroker.filestorageclient;

import java.net.URI;

public class FileStorage {

	private String storageId;
	private URI uri;
	private URI adminUri;
	private boolean readOnly;

	public FileStorage(String storageId, URI uri, URI adminUri, boolean readOnly) {
		this.storageId = storageId;
		this.uri = uri;
		this.adminUri = adminUri;
		this.readOnly = readOnly;
	}

	public FileStorage(FileStorage storage) {
		this(storage.getStorageId(), storage.getUri(), storage.getAdminUri(), storage.isReadOnly());
	}

	public String getStorageId() {
		return storageId;
	}

	public void setStorageId(String storageId) {
		this.storageId = storageId;
	}

	public URI getUri() {
		return uri;
	}

	public void setUri(URI uri) {
		this.uri = uri;
	}

	public URI getAdminUri() {
		return adminUri;
	}

	public void setAdminUri(URI adminUri) {
		this.adminUri = adminUri;
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public void setReadOnly(boolean readOnly) {
		this.readOnly = readOnly;
	}
}
