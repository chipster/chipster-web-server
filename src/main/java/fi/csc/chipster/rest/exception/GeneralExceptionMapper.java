package fi.csc.chipster.rest.exception;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * General exception mapper. Makes sure that the filters, especially the
 * CORSResponseFilter is executed also in case of unexpected exceptions, because
 * otherwise client's sees strange CORS errors instead of the
 * InternalServerError.
 * 
 * 
 * @author klemela
 */
@Provider
public class GeneralExceptionMapper implements ExceptionMapper<Throwable> {

	private static Logger logger = LogManager.getLogger();

	@Context
	UriInfo uriInfo;

	public GeneralExceptionMapper(ExceptionLogger exceptionLogger) {
	}

	@Override
	public Response toResponse(Throwable e) {
		// always log unexpected exceptions
		logger.error("unexpected exception", e);
		// don't send exception message, because, it could contain some
		// sensitive information
		return Response.status(Status.INTERNAL_SERVER_ERROR).build();
	}
}