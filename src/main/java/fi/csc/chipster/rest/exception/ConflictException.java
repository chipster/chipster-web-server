package fi.csc.chipster.rest.exception;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

public class ConflictException extends javax.ws.rs.WebApplicationException {

	public ConflictException(String message) {
		super(Response.status(Status.CONFLICT).entity(message).type(MediaType.TEXT_PLAIN).build());
	}	
}

