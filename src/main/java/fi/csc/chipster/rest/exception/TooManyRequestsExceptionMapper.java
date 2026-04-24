package fi.csc.chipster.rest.exception;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Optionally log client errors
 * 
 * @author klemela
 */
@Provider
public class TooManyRequestsExceptionMapper implements ExceptionMapper<TooManyRequestsException> {

	private ExceptionLogger exceptionLogger;

	@Context
	UriInfo uriInfo;

	public TooManyRequestsExceptionMapper(ExceptionLogger exceptionLogger) {
		this.exceptionLogger = exceptionLogger;
	}

	@Override
	public Response toResponse(TooManyRequestsException e) {
		this.exceptionLogger.log(e, uriInfo);

		return e.getResponse();
	}
}