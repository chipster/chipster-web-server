package fi.csc.chipster.rest.exception;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Don't log client errors
 * 
 * @author klemela
 */
@Provider
public class TooManyRequestsExceptionMapper implements ExceptionMapper<TooManyRequestsException> {	
	
	@Override
	public Response toResponse(TooManyRequestsException e) {
		return e.getResponse();
	}
}