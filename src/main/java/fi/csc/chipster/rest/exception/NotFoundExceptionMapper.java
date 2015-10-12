package fi.csc.chipster.rest.exception;

import javax.ws.rs.NotFoundException;
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
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {
	
	@Override
	public Response toResponse(NotFoundException e) {
		// client error, no need to log
		return Response.status(Status.NOT_FOUND).entity(e.getMessage()).build();
	}
}