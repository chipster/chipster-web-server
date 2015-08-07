package fi.csc.chipster.sessionstorage.rest.error;

import java.util.logging.Logger;

import javax.ws.rs.BadRequestException;
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
public class BadRequestExceptionMapper implements ExceptionMapper<BadRequestException> {
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(BadRequestExceptionMapper.class.getName());
	
	@Override
	public Response toResponse(BadRequestException e) {
		// client error, no need to log
		Hibernate.rollbackIfActive();
		return Response.status(Status.BAD_REQUEST).build();
	}
}