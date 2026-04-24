package fi.csc.chipster.rest.exception;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

/**
 * Optionally log client errors
 * 
 * @author klemela
 */
@Provider
public class ExceptionLogger {

	private static Logger logger = LogManager.getLogger();

	private boolean logExceptions;

	public ExceptionLogger(boolean logExceptions) {
		this.logExceptions = logExceptions;
	}

	public void log(Exception e, UriInfo uriInfo) {
		if (logExceptions) {
			// hide requests from chrome developer tools
			if (!".well-known/appspecific/com.chrome.devtools.json".equals(uriInfo.getPath())) {
				logger.warn(e.getClass().getSimpleName() + ": " + e.getMessage() + " (" + uriInfo.getPath() + ")");
			}
		}
	}
}