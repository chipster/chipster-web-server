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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.Method;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class CORSResponseFilter implements ContainerResponseFilter {
	
	private Logger logger = LogManager.getLogger();
	
	private ServiceLocatorClient serviceLocator;
	private String webServerUri; 

	public CORSResponseFilter(ServiceLocatorClient serviceLocator) {
		// get web-server uri only when it's needed, because auth initializes this before the service locator is running
		this.serviceLocator = serviceLocator;
	}
	
	
	@Override
	public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {			
		
		if (webServerUri == null) {
			try {
				webServerUri = serviceLocator.getPublicUri(Role.WEB_SERVER);
			} catch (Exception e) {
				logger.warn("cors headers not yeat available");
			}
		}
		
		if (webServerUri != null) {
			MultivaluedMap<String, Object> headers = responseContext.getHeaders();
			//headers.add("Access-Control-Allow-Origin", "*");
			headers.add("Access-Control-Allow-Origin", webServerUri);		
			headers.add("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT");			
			headers.add("Access-Control-Allow-Headers", "authorization, content-type"); // request
			headers.add("Access-Control-Expose-Headers", "location, Accept-Ranges, Retry-After"); // response
			headers.add("Access-Control-Allow-Credentials", "true");
			headers.add("Access-Control-Max-Age", "1728000"); // in seconds, 20 days
			//headers.add("Access-Control-Max-Age", "1"); // makes debugging easier
		}
				
		if (Method.OPTIONS.matchesMethod(requestContext.getMethod())) {
			// otherwise Jersey responds with some xml that Chrome doesn't like: chrome cross-origin read blocking blocked cross-origin response with mime type application/vnd.sun.wadl+xml"
			responseContext.setEntity(null, null, MediaType.APPLICATION_JSON_TYPE);
		}
	}
}