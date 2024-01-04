package fi.csc.chipster.rest.websocket;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Handle upgrade requests with a wrong path
 * 
 * It's easy to make mistakes in the encoding of the topic, but it's difficult
 * to understand what is happening, because the WebSocket endpoint methods won't
 * get called at all. This filter prints a clear warning to the server log to
 * make it
 * clearer.
 * 
 * This filter is not called at all for the succesful WebSocket requests.
 * 
 * @author klemela
 *
 */
public class PubSubNotFoundServletFilter implements Filter {

	private static final Logger logger = LogManager.getLogger();

	public PubSubNotFoundServletFilter() {
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void doFilter(ServletRequest servletRequest,
			ServletResponse servletResponse,
			FilterChain filterChain) throws IOException, ServletException {

		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;

		logger.warn("WebSocket request path not found: " + request.getRequestURI());

		response.sendError(HttpServletResponse.SC_NOT_FOUND, "not found: " + request.getRequestURI());
		return;
	}

	@Override
	public void destroy() {
	}
}