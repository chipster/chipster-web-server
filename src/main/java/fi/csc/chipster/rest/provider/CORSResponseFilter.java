package fi.csc.chipster.rest.provider;

import java.io.IOException;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;

@Provider
public class CORSResponseFilter implements ContainerResponseFilter {
	
	@Override
	public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
		
//		System.out.println(requestContext.getMethod() + " " + requestContext.getUriInfo().getBaseUri() + requestContext.getUriInfo().getPath());
//		for (String key : requestContext.getHeaders().keySet()) {
//			System.out.println(key + ": " + requestContext.getHeaderString(key));
//		}
 
		MultivaluedMap<String, Object> headers = responseContext.getHeaders();
 
		//headers.add("Access-Control-Allow-Origin", "*");
		headers.add("Access-Control-Allow-Origin", requestContext.getHeaderString("origin"));		
		headers.add("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT");			
		headers.add("Access-Control-Allow-Headers", "authorization");
		headers.add("Access-Control-Allow-Credentials", "true");
		headers.add("Access-Control-Max-Age", "1728000"); // in seconds, 20 days
		//headers.add("Access-Control-Max-Age", "1"); // makes debugging easier
		
//		System.out.println(responseContext.getStatus());
//		for (String key : responseContext.getHeaders().keySet()) {
//			System.out.println(key + ": " + responseContext.getHeaderString(key));
//		}
	}
}