package fi.csc.chipster.rest.token;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.uri.UriTemplate;

import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.rest.websocket.PrincipalRequestWrapper;
import fi.csc.chipster.rest.websocket.PubSubEndpoint;
import fi.csc.chipster.rest.websocket.TopicConfig;

public class PubSubTokenServletFilter implements Filter {

	private static final String X_FORWARDED_FOR = "X-Forwarded-For";
	
	private List<String> detailHeaders = Arrays.asList(new String[] {X_FORWARDED_FOR}); 
	
	private static final Logger logger = LogManager.getLogger();
	private TopicConfig topicCheck;
	private String pathTemplate;
    
    public PubSubTokenServletFilter(TopicConfig topicConfig, String pathTemplate) {
		this.topicCheck = topicConfig;
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

    	try {
    		AuthPrincipal principal = topicCheck.getUserPrincipal(tokenKey);
    		
    		if (principal != null) {    			
    			
    			/* topic authorization is checked twice: once here to make a client 
    			 * notice errors already in the connection handshake and the second 
    			 * time in when the connection is opened, because the topic parsing
    			 * here is little bit worrying
    			 */
    			if (isAuthorized(principal, getTopic(request))) {
    				
    				// authentication ok
    				// use PrincipalRequestWrapper to transmit username and roles
    				// to PubSubEndpoint
    				logger.debug("authentication ok");
    				
    				// transmit also remote address and X-Forwarded-For header for the admin view
    				principal.setRemoteAddress(request.getRemoteAddr());
    				
    				for (String headerName : detailHeaders) {
    					principal.getDetails().put(headerName, request.getHeader(headerName));
    				}    				    			    		    
    		    	
    				PrincipalRequestWrapper authenticatedRequest = new PrincipalRequestWrapper(principal, request);
    				
    				/* Jetty WebSocketUpgradeFilter gets the target path from getServletPath(), which
    				 * is decoded. If the topic contains an encoded slash like /events/jaas%Fchipster, getServletPath()
    				 * will return /events/jaas/chipster, which doesn't match with the pathTemplate resulting to a 404
    				 * response. Make the getServletPath() return he encoded version despite the spec to
    				 * get to the PubSubEndpoint correctly.
    				 */
    				String originalTopic = getTopic(request);
    				if (originalTopic != null) {
	    		    	String encodedTopic = URLEncoder.encode(originalTopic, StandardCharsets.UTF_8.toString());
	    		    	String forwardUri = getUrl(encodedTopic);
	    		    	authenticatedRequest.setServletPath(forwardUri);
    				}
    				
    		    	filterChain.doFilter(authenticatedRequest, response);    		    	
    				
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
    	} catch (jakarta.ws.rs.NotAuthorizedException e) {
    		response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
    		return;
    	} catch (Exception e) {
    		logger.error("error in websocket authentication", e);
    		response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "failed to retrieve the token");
    		return;
    	} 
    }
    
    public String getTopic(HttpServletRequest request) {
    	Map<String, String> groupValues = new HashMap<>();
    	String requestPath = request.getRequestURI();
		if (!new UriTemplate(pathTemplate).match(requestPath, groupValues)) {
			// matching failed
			logger.warn("uri template matching failed");
			logger.warn("template: " + pathTemplate);
			logger.warn("uri:      " + requestPath + " (" + request.getRequestURI() + ")");
			throw new NotFoundException("path " + requestPath + " not found");
		}
		
		String topic = groupValues.get(PubSubEndpoint.TOPIC_KEY);
		topic = PubSubEndpoint.decodeTopic(topic);
		return topic;
    }
    
    public String getUrl(String topic) {
    	HashMap<String, String> groupValues = new HashMap<String, String>(){{
    		put(PubSubEndpoint.TOPIC_KEY, topic);
    	}};
		return new UriTemplate(pathTemplate).createURI(groupValues);				
    }

    private boolean isAuthorized(AuthPrincipal principal, String topic) {
		return topicCheck.isAuthorized(principal, topic);
	}
	
	@Override
    public void destroy() {}
}