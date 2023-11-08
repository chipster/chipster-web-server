package fi.csc.chipster.rest.exception;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 */
@Provider
public class NotFoundRollbackExceptionMapper implements ExceptionMapper<NotFoundException> {	
	
	@Override
	public Response toResponse(NotFoundException e) {
		// client error, no need to log
		return Response.status(Status.NOT_FOUND).entity(e.getMessage()).type(MediaType.TEXT_PLAIN).build();
	}
}