package fi.csc.chipster.rest;

import java.io.IOException;
import java.util.Map.Entry;

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

import fi.csc.chipster.servicelocator.ServiceLocatorClient;

public class CORSServletFilter implements Filter {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();
	private CORSFilter corsFilter;
	
	public CORSServletFilter(ServiceLocatorClient serviceLocator) {
		this.corsFilter = new CORSFilter(serviceLocator);
	}
	
	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
			throws IOException, ServletException {	
		
		HttpServletRequest request = (HttpServletRequest)req;
		HttpServletResponse response = (HttpServletResponse)resp;
		
		String origin = request.getHeader(CORSFilter.HEADER_KEY_ORIGIN);
		
		for (Entry<String, String> entry : corsFilter.getCorsHeaders(origin).entrySet()) {
			response.addHeader(entry.getKey(), entry.getValue());
		}
		
		chain.doFilter(request, response);
	}

	@Override
	public void destroy() {
	}
}
