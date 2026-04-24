package fi.csc.chipster.rest.exception;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Optionally log client errors
 * 
 * @author klemela
 */
@Provider
public class ConflictExceptionMapper implements ExceptionMapper<ConflictException> {

	private ExceptionLogger exceptionLogger;

	@Context
	UriInfo uriInfo;

	public ConflictExceptionMapper(ExceptionLogger exceptionLogger) {
		this.exceptionLogger = exceptionLogger;
	}

	@Override
	public Response toResponse(ConflictException e) {
		this.exceptionLogger.log(e, uriInfo);

		return Response.status(Status.CONFLICT).entity(e.getMessage()).type(MediaType.TEXT_PLAIN).build();
	}
}