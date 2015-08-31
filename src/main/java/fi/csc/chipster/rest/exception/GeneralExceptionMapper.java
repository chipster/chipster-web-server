package fi.csc.chipster.rest.exception;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

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

	private static Logger logger = Logger
			.getLogger(GeneralExceptionMapper.class.getName());

	@Override
	public Response toResponse(Throwable e) {
		logger.log(Level.SEVERE, "unexpected exception", e);
		// don't send exception message, because, it could contain some
		// sensitive information
		return Response.status(Status.INTERNAL_SERVER_ERROR).build();
	}
}