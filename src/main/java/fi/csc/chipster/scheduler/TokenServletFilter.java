package fi.csc.chipster.scheduler;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.NotFoundException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;

public class TokenServletFilter implements Filter {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();
	private AuthenticationClient authService;

    public TokenServletFilter(AuthenticationClient authService) {
		this.authService = authService;
	}

	@Override
    public void init(FilterConfig filterConfig) throws ServletException {}

    @Override
    public void doFilter(ServletRequest servletRequest, 
                         ServletResponse servletResponse, 
                         FilterChain filterChain) throws IOException, ServletException {
    	    	
    	HttpServletRequest request = (HttpServletRequest) servletRequest;
    	HttpServletResponse response = (HttpServletResponse) servletResponse;
    	
    	String tokenKey = request.getParameter("token");
    	
    	if (tokenKey == null) {
    		response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "");
    	}
    	
    	try {
    		Token dbToken = authService.getDbToken(tokenKey);
    		
    		if (dbToken != null && dbToken.getRoles().contains(Role.SERVER)) {
    			// authentication ok
    			filterChain.doFilter(servletRequest, servletResponse);
    			return;
    		}
    	} catch (NotFoundException e) {
    		response.sendError(HttpServletResponse.SC_FORBIDDEN, "");	
    	}
    	
    	// authentication failed
    	response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "");    		
    }

    @Override
    public void destroy() {}

}