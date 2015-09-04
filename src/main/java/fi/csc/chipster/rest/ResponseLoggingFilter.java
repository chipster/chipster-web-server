package fi.csc.chipster.rest;

import java.io.IOException;
import java.io.InputStream;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.ext.Provider;

import org.apache.commons.io.IOUtils;

@Provider
public class ResponseLoggingFilter implements ContainerResponseFilter {
	
	private boolean logRequests = true;
	private boolean logRequestHeaders = true;
	private boolean logRequestBody = true;
	
	private boolean logResponses = true;
	private boolean logResponseHeaders = true;
	private boolean logResponseBody = true;
	
	public ResponseLoggingFilter() { }
	
	public ResponseLoggingFilter(boolean enabled) {
		this(enabled, enabled, enabled, enabled, enabled, enabled);
	}
	
	public ResponseLoggingFilter(boolean requests, boolean responses) {
		this(requests, requests, requests, responses, responses, responses);
	}
	
	public ResponseLoggingFilter(boolean logRequests, boolean logRequestHeaders, boolean logRequestBody, boolean logResponses, boolean logResponseHeaders, boolean logResponseBody) {
		this.logRequests = logRequests;
		this.logRequestHeaders = logRequestHeaders;
		this.logRequestBody = logRequestBody;
		this.logResponses = logResponses;
		this.logResponseHeaders = logResponseHeaders;
		this.logResponseBody = logResponseBody;
	}

	public ResponseLoggingFilter(boolean requestAndResponses, boolean headers, boolean bodies) {
		this(requestAndResponses, headers, bodies, requestAndResponses, headers, bodies);
	}

	@Override
	public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
		
//		logRequests = true;
//		logRequestHeaders = true;
//		logRequestBody = true;
//		
//		logResponses = true;
//		logResponseHeaders = true;
//		logResponseBody = true;
		
		// printing everything at once makes it less likely for other parallel
		// requests to come in between
		String log = "";

		if (logRequests) {
			log += requestContext.getMethod() + " " + requestContext.getUriInfo().getBaseUri() + requestContext.getUriInfo().getPath() + "\n";
		}
		
		if (logRequestHeaders) {
			for (String key : requestContext.getHeaders().keySet()) {
				log += key + ": " + requestContext.getHeaderString(key) + "\n";
			}
		}
		
		if (logRequestBody) {
//TODO can't read same stream twice
//			System.out.println(IOUtils.toString(requestContext.getEntityStream()));
		}
 
		
		if (logResponses) {
			log += responseContext.getStatus() + " " + responseContext.getStatusInfo().getReasonPhrase() + "\n";
		}
		
		if (logResponseHeaders) {
			for (String key : responseContext.getHeaders().keySet()) {
				log += key + ": " + responseContext.getHeaderString(key) + "\n";
			}
		}
		
		if (logResponseBody) {
			Object entity = responseContext.getEntity();
			if (entity instanceof InputStream) {
				entity = IOUtils.toString((InputStream)entity);
			}
			log += RestUtils.asJson(entity) + "\n";
		}
		
		System.out.println(log);
	}
}