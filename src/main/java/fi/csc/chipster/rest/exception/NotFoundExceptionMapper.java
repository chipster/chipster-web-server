package fi.csc.chipster.rest.exception;

import jakarta.ws.rs.NotFoundException;
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
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {

	private ExceptionLogger exceptionLogger;

	@Context
	UriInfo uriInfo;

	public NotFoundExceptionMapper(ExceptionLogger exceptionLogger) {
		this.exceptionLogger = exceptionLogger;
	}

	@Override
	public Response toResponse(NotFoundException e) {
		this.exceptionLogger.log(e, uriInfo);

		return Response.status(Status.NOT_FOUND).entity(e.getMessage()).type(MediaType.TEXT_PLAIN).build();
	}
}