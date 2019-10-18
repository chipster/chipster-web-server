package fi.csc.chipster.rest;

import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

/**
 * Generic CORS filter
 * 
 * Used both in CORSResponseFilter for Jersey and CORSServletFilter for file-broker's FileServlet.
 * 
 * @author klemela
 *
 */
public class CORSFilter {
	
	public static final String HEADER_KEY_ORIGIN = "Origin";

	private Logger logger = LogManager.getLogger();

	private ServiceLocatorClient serviceLocator;
	private volatile Set<String> webServerUris; 

	public CORSFilter(ServiceLocatorClient serviceLocator) {
		// get web-server uri only when it's needed, because auth initializes this before the service locator is running
		this.serviceLocator = serviceLocator;
	}

	public HashMap<String, String> getCorsHeaders(String origin) {

		// double checked locking with volatile field http://rpktech.com/2015/02/04/lazy-initialization-in-multi-threaded-environment/
		// to make this safe and relatively fast for multi-thread usage
		if (webServerUris == null) {			
			synchronized (CORSResponseFilter.class) {
				if (webServerUris == null) {
					webServerUris = getWebServerUris(serviceLocator);
				}
			}
		}

		HashMap<String, String> headers = new HashMap<>();
		if (webServerUris != null) {

			if (webServerUris.contains(origin)) {

				headers.put("Access-Control-Allow-Origin", origin);		
				headers.put("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT");			
				headers.put("Access-Control-Allow-Headers", "authorization, content-type, range"); // request
				headers.put("Access-Control-Expose-Headers", "location, Accept-Ranges, Retry-After"); // response
				headers.put("Access-Control-Allow-Credentials", "true");
				headers.put("Access-Control-Max-Age", "" + (60 * 60 * 24)); // in seconds, 1 day
				//headers.put("Access-Control-Max-Age", "1"); // makes debugging easier

			} else {
				if (origin != null) {
					logger.info("cors headers not added for origin: '" + origin + "'");
				}
			}
		}
		return headers;

	}


	private Set<String> getWebServerUris(ServiceLocatorClient serviceLocator) {
		long t = 0;
		try {
			logger.info("get cors origin from " + serviceLocator.getBaseUri());
			t = System.currentTimeMillis();

			// allow one backend to serve multiple web-servers (Chipster and Mylly)
			return serviceLocator.getPublicServices().stream()
					.filter(s -> s.getRole().startsWith(Role.WEB_SERVER))
					.map(s -> s.getPublicUri())
					.collect(Collectors.toSet());

		} catch (Exception e) {
			logger.warn("cors headers not yeat available (request took " + (System.currentTimeMillis() - t) + "ms)");
			return null;
		}
	}

}
