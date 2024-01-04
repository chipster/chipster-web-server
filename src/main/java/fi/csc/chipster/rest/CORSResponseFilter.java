package fi.csc.chipster.rest;

import java.io.IOException;
import java.util.Map.Entry;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.Method;

import fi.csc.chipster.servicelocator.ServiceLocatorClient;

@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class CORSResponseFilter implements ContainerResponseFilter {

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private CORSFilter corsFilter;

	public CORSResponseFilter(ServiceLocatorClient serviceLocator) {
		this.corsFilter = new CORSFilter(serviceLocator);
	}

	@Override
	public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
			throws IOException {

		String origin = requestContext.getHeaderString(CORSFilter.HEADER_KEY_ORIGIN);

		// if origin is null, assume that the caller isn't a browser and doesn't need
		// CORS headers
		if (origin != null) {

			MultivaluedMap<String, Object> headers = responseContext.getHeaders();
			for (Entry<String, String> entry : corsFilter.getCorsHeaders(origin).entrySet()) {
				headers.add(entry.getKey(), entry.getValue());
			}

			if (Method.OPTIONS.matchesMethod(requestContext.getMethod())) {
				// otherwise Jersey responds with some xml that Chrome doesn't like: chrome
				// cross-origin read blocking blocked cross-origin response with mime type
				// application/vnd.sun.wadl+xml"
				responseContext.setEntity(null, null, MediaType.APPLICATION_JSON_TYPE);
			}
		}
	}
}