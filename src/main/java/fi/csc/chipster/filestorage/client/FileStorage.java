package fi.csc.chipster.filestorage.client;

import java.net.URI;

public class FileStorage {

	private String storageId;
	private URI uri;
	private URI internalAdminUri;
	private boolean readOnly;

	public FileStorage(String storageId, URI uri, URI internalAdminUri, boolean readOnly) {
		this.storageId = storageId;
		this.uri = uri;
		this.internalAdminUri = internalAdminUri;
		this.readOnly = readOnly;
	}

	public FileStorage(FileStorage storage) {
		this(storage.getStorageId(), storage.getUri(), storage.getInternalAdminUri(), storage.isReadOnly());
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

	public URI getInternalAdminUri() {
		return internalAdminUri;
	}

	public void setInternalAdminUri(URI adminUri) {
		this.internalAdminUri = adminUri;
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public void setReadOnly(boolean readOnly) {
		this.readOnly = readOnly;
	}
}
