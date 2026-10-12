package fi.csc.chipster.rest;

import java.time.Duration;
import java.util.HashMap;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

/**
 * Generic CORS filter
 * 
 * Used both in CORSResponseFilter for Jersey and CORSServletFilter for
 * file-broker's FileServlet.
 * 
 * @author klemela
 *
 */
public class CORSFilter {

    public static final String HEADER_KEY_ORIGIN = "Origin";

    private Logger logger = LogManager.getLogger();

    // for connecting and for reading the response, each, so that the requests
    // don't hang if the service-locator does
    private static final Duration SERVICE_LOCATOR_TIMEOUT = Duration.ofSeconds(1);

    private ServiceLocatorClient serviceLocator;
    private volatile Set<String> webServerUris;

    public CORSFilter(ServiceLocatorClient serviceLocator) {
        // get web-server uri only when it's needed, because auth initializes this
        // before the service locator is running
        this.serviceLocator = serviceLocator;
    }

    public HashMap<String, String> getCorsHeaders(String origin) {

        HashMap<String, String> headers = new HashMap<>();

        // the caller isn't a browser and doesn't need the headers
        if (origin == null) {
            return headers;
        }

        /*
         * No lock: each request gets the uris until one of them has stored them.
         * After a start that costs a few extra requests to the service-locator,
         * but a request doesn't go without the headers only because another
         * request is getting them at the same time. If the service-locator
         * doesn't respond, every browser request waits for its own request to
         * time out (up to 2 seconds) and goes without the headers.
         */
        Set<String> webServerUris = this.webServerUris;
        if (webServerUris == null) {
            webServerUris = getWebServerUris(serviceLocator);
            if (webServerUris != null) {
                this.webServerUris = webServerUris;
            }
        }

        if (webServerUris == null) {
            logger.warn("cors headers not added for origin: '" + origin
                    + "', because the allowed origins are not available yet");
        } else {

            if (webServerUris.contains(origin)) {

                headers.put("Access-Control-Allow-Origin", origin);
                headers.put("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT");
                headers.put("Access-Control-Allow-Headers", "authorization, content-type, range"); // request
                headers.put("Access-Control-Expose-Headers", "location, Accept-Ranges, Retry-After"); // response
                headers.put("Access-Control-Allow-Credentials", "true");
                headers.put("Access-Control-Max-Age", "" + (60 * 60 * 24)); // in seconds, 1 day
                // headers.put("Access-Control-Max-Age", "1"); // makes debugging easier

            } else {
                logger.info("cors headers not added for origin: '" + origin + "'");
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
            return serviceLocator.getPublicUris(Role.WEB_SERVER, SERVICE_LOCATOR_TIMEOUT);

        } catch (Exception e) {
            logger.warn("cors headers not yeat available (request took " + (System.currentTimeMillis() - t) + "ms)");
            return null;
        }
    }

}
