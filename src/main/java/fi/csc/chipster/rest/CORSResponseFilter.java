package fi.csc.chipster.rest;

import java.io.IOException;

import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;

import org.glassfish.grizzly.http.Method;

import fi.csc.chipster.auth.model.Role;

@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class CORSResponseFilter implements ContainerResponseFilter {
	
	
	@Override
	public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {			
		Config config = new Config();
		
		MultivaluedMap<String, Object> headers = responseContext.getHeaders();
 
		//headers.add("Access-Control-Allow-Origin", "*");
		headers.add("Access-Control-Allow-Origin", config.getExternalServiceUrls().get(Role.WEB_SERVER));		
		headers.add("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT");			
		headers.add("Access-Control-Allow-Headers", "authorization, content-type"); // request
		headers.add("Access-Control-Expose-Headers", "location, Accept-Ranges, Retry-After"); // response
		headers.add("Access-Control-Allow-Credentials", "true");
		headers.add("Access-Control-Max-Age", "1728000"); // in seconds, 20 days
		//headers.add("Access-Control-Max-Age", "1"); // makes debugging easier
				
		if (Method.OPTIONS.matchesMethod(requestContext.getMethod())) {
			// otherwise Jersey responds with some xml that Chrome doesn't like: chrome cross-origin read blocking blocked cross-origin response with mime type application/vnd.sun.wadl+xml"
			responseContext.setEntity(null, null, MediaType.APPLICATION_JSON_TYPE);
		}
	}
}