package fi.csc.chipster.rest.exception;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.base.JsonMappingExceptionMapper;

import com.fasterxml.jackson.databind.JsonMappingException;

import jakarta.ws.rs.core.Response;

public class ChipsterJsonMappingExceptionMapper extends JsonMappingExceptionMapper {
	
	private static Logger logger = LogManager.getLogger();
	
    @Override
    public Response toResponse(JsonMappingException exception) {
    	
    	// backend produced invalid json
    	logger.error("json mapping exception", exception);
    	
        return super.toResponse(exception);
    }
}
