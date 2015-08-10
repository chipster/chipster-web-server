package fi.csc.chipster.sessionstorage.rest.error;

import java.util.logging.Logger;

import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import fi.csc.chipster.sessionstorage.rest.Hibernate;

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
		Hibernate.rollbackIfActive();
		return Response.status(Status.UNAUTHORIZED).build();
	}
}