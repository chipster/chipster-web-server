package fi.csc.chipster.rest.exception;

import jakarta.ws.rs.NotAuthorizedException;
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
public class NotAuthorizedExceptionMapper implements ExceptionMapper<NotAuthorizedException> {

	private ExceptionLogger exceptionLogger;

	@Context
	UriInfo uriInfo;

	public NotAuthorizedExceptionMapper(ExceptionLogger exceptionLogger) {
		this.exceptionLogger = exceptionLogger;
	}

	@Override
	public Response toResponse(NotAuthorizedException e) {
		this.exceptionLogger.log(e, uriInfo);

		return Response.status(Status.UNAUTHORIZED).entity(e.getMessage()).type(MediaType.TEXT_PLAIN).build();
	}
}