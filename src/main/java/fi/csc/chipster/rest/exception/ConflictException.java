package fi.csc.chipster.rest.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

public class ConflictException extends jakarta.ws.rs.WebApplicationException {

	public ConflictException(String message) {
		super(Response.status(Status.CONFLICT).entity(message).type(MediaType.TEXT_PLAIN).build());
	}	
}

