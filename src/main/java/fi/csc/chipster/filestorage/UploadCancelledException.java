package fi.csc.chipster.filestorage;

import jakarta.ws.rs.BadRequestException;

public class UploadCancelledException extends BadRequestException {

	public UploadCancelledException(String msg) {
		super(msg);
	}
}
