package fi.csc.chipster.rest.exception;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * Why GeneralExceptionMapper doesn't catch this?
 * 
 * @author klemela
 */
@Provider
public class JsonExceptionMapper implements ExceptionMapper<JsonMappingException> {

	private static Logger logger = Logger
			.getLogger(JsonExceptionMapper.class.getName());

	@Override
	public Response toResponse(JsonMappingException e) {
		logger.log(Level.SEVERE, "JSON mapping exception", e);
		//TODO configure logging to show in eclipse console
		e.printStackTrace();
		// don't send exception message, because, it could contain some
		// sensitive information
		return Response.status(Status.INTERNAL_SERVER_ERROR).build();
	}
}