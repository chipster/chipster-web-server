package fi.csc.chipster.filestorage;

import javax.ws.rs.BadRequestException;

public class UploadCancelledException extends BadRequestException {

	public UploadCancelledException(String msg) {
		super(msg);
	}
}
