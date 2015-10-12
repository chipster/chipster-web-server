package fi.csc.chipster.rest.exception;

import javax.ws.rs.BadRequestException;
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
public class BadRequestExceptionMapper implements ExceptionMapper<BadRequestException> {	
	
	@Override
	public Response toResponse(BadRequestException e) {
		// client error, no need to log
		return Response.status(Status.BAD_REQUEST).entity(e.getMessage()).build();	
	}
}