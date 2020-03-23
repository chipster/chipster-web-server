package fi.csc.chipster.rest.exception;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

/**
 * Don't log client errors
 * 
 * @author klemela
 */
@Provider
public class ConflictExceptionMapper implements ExceptionMapper<ConflictException> {	

	@Override
	public Response toResponse(ConflictException e) {
		// client error, no need to log
		return Response.status(Status.CONFLICT).entity(e.getMessage()).type(MediaType.TEXT_PLAIN).build();
	}
}