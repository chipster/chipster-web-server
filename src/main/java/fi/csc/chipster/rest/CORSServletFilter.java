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

public class CORSServletFilter implements Filter {

	private Config config;
	
	
	public CORSServletFilter(Config config) {
		this.config= config;
	}
	
	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
			throws IOException, ServletException {	
		
		HttpServletRequest request = (HttpServletRequest)req;
		HttpServletResponse response = (HttpServletResponse)resp;
	
		
		//dont take this from the req as it is a security threat, get the address from the config file
		//response.addHeader("Access-Control-Allow-Origin", this.config.getExternalServiceUrls().get(Role.WEB_SERVER));	
		response.addHeader("Access-Control-Allow-Origin", request.getHeader("origin"));
		response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT");			
		response.addHeader("Access-Control-Allow-Headers", "authorization, content-type, range"); // request
		response.addHeader("Access-Control-Expose-Headers", "location, Accept-Ranges, Content-Encoding, Content-Length, Accept-Ranges, Content-Range"); // response
		response.addHeader("Access-Control-Allow-Credentials", "true");
		response.addHeader("Access-Control-Max-Age", "1728000"); // in seconds, 20 days
		//response.addHeader("Access-Control-Max-Age", "1"); // makes debugging easier
		
		chain.doFilter(request, response);
	}

	@Override
	public void destroy() {
	}
}
