package fi.csc.chipster.rest.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

public class NotAuthorizedException extends jakarta.ws.rs.NotAuthorizedException {

	public NotAuthorizedException(String message) {
		super(Response.status(Status.UNAUTHORIZED).entity(message).type(MediaType.TEXT_PLAIN).build());
	}
}
