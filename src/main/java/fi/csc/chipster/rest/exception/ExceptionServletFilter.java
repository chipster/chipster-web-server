package fi.csc.chipster.rest.exception;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotFoundException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ExceptionServletFilter implements Filter {
	
	private static final Logger logger = LogManager.getLogger();

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
			throws IOException, ServletException {	
		
		HttpServletRequest request = (HttpServletRequest)req;
		HttpServletResponse response = (HttpServletResponse)resp;
		
		try {
		
			chain.doFilter(request, response);
		} catch (ForbiddenException e) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, e.getMessage());
			return;
		} catch (NotFoundException e) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
			return;
		} catch (BadRequestException e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
			return;
		} catch (javax.ws.rs.NotAuthorizedException e) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
			return;
		} catch (ConflictException e) {
			response.sendError(HttpServletResponse.SC_CONFLICT, e.getMessage());
			return;	
		} catch (Exception e) {
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			logger.error("authentication error", e);
			return;
		}
	}

	@Override
	public void destroy() {
	}
}
