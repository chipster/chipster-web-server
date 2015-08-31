package fi.csc.chipster.rest;

import java.io.IOException;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.ext.Provider;

import org.apache.commons.io.IOUtils;

@Provider
@PreMatching
public class RequestLoggingFilter implements ContainerRequestFilter {
	
	private boolean logRequests = true;
	private boolean logRequestHeaders = true;
	private boolean logRequestBody = true;
		
	public RequestLoggingFilter() { }
	
	public RequestLoggingFilter(boolean enabled) {
		this(enabled, enabled, enabled);
	}
	
	public RequestLoggingFilter(boolean logRequests, boolean logRequestHeaders, boolean logRequestBody) {
		this.logRequests = logRequests;
		this.logRequestHeaders = logRequestHeaders;
		this.logRequestBody = logRequestBody;
	}

	@Override
	public void filter(ContainerRequestContext requestContext) throws IOException {
		
//		logRequests = true;
//		logRequestHeaders = true;
//		logRequestBody = true;

		if (logRequests) {
			System.out.println(requestContext.getMethod() + " " + requestContext.getUriInfo().getBaseUri() + requestContext.getUriInfo().getPath());
		}
		
		if (logRequestHeaders) {
			for (String key : requestContext.getHeaders().keySet()) {
				System.out.println(key + ": " + requestContext.getHeaderString(key));
			}
		}
		
		if (logRequestBody) {
			System.out.println(IOUtils.toString(requestContext.getEntityStream()));
		}		
	}
}