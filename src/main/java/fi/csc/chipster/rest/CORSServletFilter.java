package fi.csc.chipster.rest;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

public class CORSServletFilter implements Filter {

	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();
	
	private ServiceLocatorClient serviceLocator;	
	
	public CORSServletFilter(ServiceLocatorClient serviceLocator) {
		this.serviceLocator = serviceLocator;
	}
	
	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
			throws IOException, ServletException {	
		
		HttpServletRequest request = (HttpServletRequest)req;
		HttpServletResponse response = (HttpServletResponse)resp;
	
		response.addHeader("Access-Control-Allow-Origin", serviceLocator.getPublicUri(Role.WEB_SERVER));		
		response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT");			
		response.addHeader("Access-Control-Allow-Headers", "authorization, content-type, range"); // request
		response.addHeader("Access-Control-Expose-Headers", "location, Accept-Ranges, Content-Encoding, Content-Length, Accept-Ranges, Content-Range"); // response
		response.addHeader("Access-Control-Allow-Credentials", "true");
		response.addHeader("Access-Control-Max-Age", "" + (60 * 60 * 24)); // in seconds, 1 day
		//response.addHeader("Access-Control-Max-Age", "1"); // makes debugging easier
		
		chain.doFilter(request, response);
	}

	@Override
	public void destroy() {
	}
}
