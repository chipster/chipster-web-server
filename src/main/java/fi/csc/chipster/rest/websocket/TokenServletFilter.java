package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotFoundException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.uri.UriTemplate;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.rest.websocket.PubSubServer.TopicCheck;

public class TokenServletFilter implements Filter {
	
	private static final Logger logger = LogManager.getLogger();
	private AuthenticationClient authService;
	private TopicCheck topicCheck;
	private String pathTemplate;
    
    public TokenServletFilter(AuthenticationClient authService, TopicCheck topicAuthorization, String pathTemplate) {
		this.authService = authService;
		this.topicCheck = topicAuthorization;
		this.pathTemplate = pathTemplate;
	}

	@Override
    public void init(FilterConfig filterConfig) throws ServletException {}

    @Override
    public void doFilter(ServletRequest servletRequest, 
                         ServletResponse servletResponse, 
                         FilterChain filterChain) throws IOException, ServletException {
    	    	
    	HttpServletRequest request = (HttpServletRequest) servletRequest;
    	HttpServletResponse response = (HttpServletResponse) servletResponse;
    	
    	logger.debug("authenticate request " + request.getRequestURI());
    	
    	String tokenKey = request.getParameter("token");
    	
    	if (tokenKey == null) {
    		logger.debug("no token");
    		response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "no token in request");
    		return;
    	}
    	
    	Token dbToken = null;
    	
    	try {
    		dbToken = authService.getDbToken(tokenKey);


    		if (dbToken != null) {    		
    			AuthPrincipal principal = new AuthPrincipal(dbToken.getUsername(), dbToken.getRoles());

    			/* topic authorization is checked twice: ones here to make a client 
    			 * notice errors already in the connection handshake and the second 
    			 * time in when the connection is opened, because the topic parsing
    			 * here is little bit worrying
    			 */
    			if (isAuthorized(principal, request)) {
    				// authentication ok
    				// use PrincipalRequestWrapper to transmit username and roles
    				// to PubSubEndpoint
    				logger.debug("authentication ok");
    				filterChain.doFilter(new PrincipalRequestWrapper(principal, request), response);
    				return;
    			} else {
    				logger.debug("access denied");
    				response.sendError(HttpServletResponse.SC_FORBIDDEN, "access denied");
    				return;
    			}
    		} else {
    			logger.debug("token not found");
    			response.sendError(HttpServletResponse.SC_FORBIDDEN, "token not found from server");
    			return;
    		}    		
    	} catch (NotFoundException e) {
    		response.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
    		return;
    	} catch (ForbiddenException e) {
    		response.sendError(HttpServletResponse.SC_FORBIDDEN, e.getMessage());
    		return;
    	} catch (javax.ws.rs.NotAuthorizedException e) {
    		response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
    		return;
    	} catch (Exception e) {
    		logger.error("error in websocket authentication", e);
    		response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "failed to retrieve the token");
    		return;
    	} 
    }

    private boolean isAuthorized(AuthPrincipal principal, HttpServletRequest request) {
    	Map<String, String> groupValues = new HashMap<>();
    	String requestPath = URI.create(request.getRequestURI()).getPath();
		if (!new UriTemplate(pathTemplate).match(requestPath, groupValues)) {
			// matching failed
			logger.debug("uri template matching failed");
			logger.debug("template: " + pathTemplate);
			logger.debug("uri:      " + requestPath);
			return false;
		}
		
		String topic = groupValues.get(PubSubEndpoint.TOPIC_KEY);
		
		return topicCheck.isAuthorized(principal, topic);
	}
	
	@Override
    public void destroy() {}
}