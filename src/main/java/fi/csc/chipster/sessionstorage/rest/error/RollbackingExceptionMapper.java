package fi.csc.chipster.sessionstorage.rest.error;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import fi.csc.chipster.sessionstorage.rest.Hibernate;

@Provider
public class RollbackingExceptionMapper implements ExceptionMapper<Throwable> {
	
	private static Logger logger = Logger.getLogger(RollbackingExceptionMapper.class.getName());
	
	@Override
	public Response toResponse(Throwable e) {
		Hibernate.rollbackIfActive();
		logger.log(Level.SEVERE, "transaction failed", e);
		return Response.serverError().build();
	}
}