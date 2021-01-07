package fi.csc.chipster.rest.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class InsufficientStorageException extends jakarta.ws.rs.WebApplicationException {

	public static final int STATUS_CODE = 507;

	public InsufficientStorageException(String message) {
		super(Response.status(STATUS_CODE).entity(message).type(MediaType.TEXT_PLAIN).build());
	}	
}

