package fi.csc.chipster.rest.exception;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonParseException;

import jakarta.ws.rs.core.Response;

public class ChipsterJsonParseExceptionMapper extends org.glassfish.jersey.jackson.internal.jackson.jaxrs.base.JsonParseExceptionMapper {
	
	private static Logger logger = LogManager.getLogger();
	
    @Override
    public Response toResponse(JsonParseException exception) {
    	
    	// invalid json from client, maybe no need for the stack trace
    	logger.warn("unable to parse json", exception.getMessage());
    	
        return super.toResponse(exception);
    }
}
