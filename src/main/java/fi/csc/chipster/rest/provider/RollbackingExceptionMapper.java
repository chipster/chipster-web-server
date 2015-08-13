package fi.csc.chipster.rest.provider;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import fi.csc.chipster.rest.Hibernate;

@Provider
public class RollbackingExceptionMapper implements ExceptionMapper<Throwable> {
	
	private static Logger logger = Logger.getLogger(RollbackingExceptionMapper.class.getName());
	
	@Override
	public Response toResponse(Throwable e) {
		//Hibernate.rollbackIfActive();
		logger.log(Level.SEVERE, "transaction failed", e);
		// don't send the message of the unexpected exceptions, because those
		// could contain sensitive information
		return Response.serverError().build();
	}
}