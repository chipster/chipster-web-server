package fi.csc.chipster.rest.exception;

import java.util.logging.Logger;

import javax.ws.rs.NotAuthorizedException;
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
public class NotAuthorizedExceptionMapper implements ExceptionMapper<NotAuthorizedException> {
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(NotAuthorizedExceptionMapper.class.getName());
	
	@Override
	public Response toResponse(NotAuthorizedException e) {
		// client error, no need to log
		return Response.status(Status.UNAUTHORIZED).entity(e.getMessage()).build();
	}
}