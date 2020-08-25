package fi.csc.chipster.rest;

import java.io.IOException;
import java.util.Map.Entry;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.Method;

import fi.csc.chipster.servicelocator.ServiceLocatorClient;

public class CORSServletFilter implements Filter {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private CORSFilter corsFilter; 

	public CORSServletFilter(ServiceLocatorClient serviceLocator) {
		this.corsFilter = new CORSFilter(serviceLocator);
	}



	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
			throws IOException, ServletException {
		
		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;
		
		String origin = request.getHeader(CORSFilter.HEADER_KEY_ORIGIN);
	
		for (Entry<String, String> entry : corsFilter.getCorsHeaders(origin).entrySet()) {
			response.addHeader(entry.getKey(), entry.getValue());
		}			
				
		if (Method.OPTIONS.matchesMethod(request.getMethod())) {
			// otherwise Jersey responds with some xml that Chrome doesn't like: chrome cross-origin read blocking blocked cross-origin response with mime type application/vnd.sun.wadl+xml"
			ServletOutputStream out = response.getOutputStream();
			out.write(new byte[0]);
			out.close();
			response.addHeader("Content-Type", MediaType.APPLICATION_JSON_TYPE.getType());
		}
		
		chain.doFilter(request, servletResponse);
	}


	@Override
	public void init(FilterConfig filterConfig) throws ServletException {		
	}
	
	@Override
	public void destroy() {		
	}
}