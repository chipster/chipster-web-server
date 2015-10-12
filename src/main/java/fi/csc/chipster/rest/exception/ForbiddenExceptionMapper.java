package fi.csc.chipster.rest.exception;

import javax.ws.rs.ForbiddenException;
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
public class ForbiddenExceptionMapper implements ExceptionMapper<ForbiddenException> {	
	
	@Override
	public Response toResponse(ForbiddenException e) {
		// client error, no need to log
		return Response.status(Status.FORBIDDEN).entity(e.getMessage()).build();
	}
}