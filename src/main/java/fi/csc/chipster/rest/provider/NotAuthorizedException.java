package fi.csc.chipster.rest.provider;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

public class NotAuthorizedException extends javax.ws.rs.NotAuthorizedException {

	public NotAuthorizedException(String message) {
		super(Response.status(Status.UNAUTHORIZED).entity(message).build());
	}	
}

