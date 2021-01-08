package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.websocket.jakarta.server.internal.JakartaWebSocketCreator;

import fi.csc.chipster.auth.resource.AuthPrincipal;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;

/**
 * A stateless endpoint class for javax.websocket API. Gets the PubSubServer 
 * instance from the user properties to do most of the actual work. 
 * 
 * @author klemela
 */

@ClientEndpoint
@ServerEndpoint(value = "/")
public class PubSubEndpoint {

	public static final Logger logger = LogManager.getLogger();

	public static final String TOPIC_KEY = "topic-name";

	private PubSubServer server;

	@OnOpen
	public void onOpen(final Session session, EndpointConfig config) {

		session.setMaxIdleTimeout(this.server.getIdleTimeout());

		Map<String, String> pathParameters = session.getPathParameters();
		Map<String, List<String>> requestParameters = session.getRequestParameterMap();
		Map<String, Object> userProperties = session.getUserProperties();

		InetSocketAddress remoteSocketAddress = (InetSocketAddress) userProperties.get(JakartaWebSocketCreator.PROP_REMOTE_ADDRESS);
		String remoteAddress = remoteSocketAddress.getAddress().getHostAddress();
		Map<String, String> details = new HashMap<>();
		details.put(PubSubConfigurator.X_FORWARDED_FOR, (String)userProperties.get(PubSubConfigurator.X_FORWARDED_FOR));


		List<String> tokenParameters = requestParameters.get("token");
		
		try {
			if (tokenParameters == null) {
				logger.debug("no token parameter");
				session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "no token in request"));
				//response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "no token in request");
				return;
			}
			
			String tokenKey = tokenParameters.get(0);

			if (tokenKey == null) {
				logger.debug("no token");
				session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "no token in request"));
				//response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "no token in request");
				return;
			}

			String topic = null;
			AuthPrincipal principal = null;

			try {
				principal = server.getTopicConfig().getUserPrincipal(tokenKey);

				if (principal == null) {
					session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "access denied"));
					return;
				}

				// get topic from path params    
				topic = getTopic(session);

				boolean isAuthorized = this.server.isTopicAuthorized(principal, topic);

				if (!isAuthorized) {
					session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "token not accepted"));
					return;
				}

				// authentication ok
				logger.debug("authentication ok");


				//    				/* Jetty WebSocketUpgradeFilter gets the target path from getServletPath(), which
				//    				 * is decoded. If the topic contains an encoded slash like /events/jaas%Fchipster, getServletPath()
				//    				 * will return /events/jaas/chipster, which doesn't match with the pathTemplate resulting to a 404
				//    				 * response. Make the getServletPath() return he encoded version despite the spec to
				//    				 * get to the PubSubEndpoint correctly.
				//    				 */
				//    				String originalTopic = getTopic(session);
				//    				if (originalTopic != null) {
				//	    		    	String encodedTopic = URLEncoder.encode(originalTopic, StandardCharsets.UTF_8.toString());
				//	    		    	String forwardUri = getUrl(encodedTopic);
				//	    		    	authenticatedRequest.setServletPath(forwardUri);
				//    				}


			} catch (NotFoundException e) {
				session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "not found: " + e.getMessage()));
				//response.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
				return;
			} catch (ForbiddenException e) {
				session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "forbidden: " + e.getMessage()));
				//    		response.sendError(HttpServletResponse.SC_FORBIDDEN, e.getMessage());
				return;
			} catch (jakarta.ws.rs.NotAuthorizedException e) {
				session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "not authorized: " + e.getMessage()));
				//    		response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
				return;
			} catch (Exception e) {
				logger.error("error in websocket authentication", e);
				session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "internal server error"));
				//    		response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "failed to retrieve the token");
				return;
			}

			// store topic to user properties, because we need it when we unsubscribe
			session.getUserProperties().put(TOPIC_KEY, topic);

			// subscribe for server messages

			Subscriber subscriber = new Subscriber(
					session.getBasicRemote(), 
					remoteAddress,
					details,
					principal.getName());

			this.server.subscribe(topic, subscriber);

			// listen for client replies
			MessageHandler messageHandler = this.server.getMessageHandler();
			if (messageHandler != null) {
				session.addMessageHandler(messageHandler);
			}
		} catch (IOException e) {
			logger.error("opening websocket failed", e);
		}
	}

	private String getTopic(Session session) {
		String topic = session.getPathParameters().get(TOPIC_KEY);    	
		return decodeTopic(topic);
	}

	public static String decodeTopic(String topic) {
		if (topic == null) {
			return null;
		}
		// why jetty decodes the url (and consequently doesn't match the WebsocketEndpoint)
		// if the slash is decoded just once?
		String decodedOnce;
		try {
			decodedOnce = URLDecoder.decode(topic, StandardCharsets.UTF_8.toString());
			String decodedTwice = URLDecoder.decode(decodedOnce, StandardCharsets.UTF_8.toString());
			return decodedTwice;

		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException("topic decode failed", e);
		}
	}

	private void close(Session session, CloseCodes closeCode, String reason) {
		try {
			session.close(new CloseReason(closeCode, reason));
		} catch (IOException ex) {
			logger.error("failed to close websocket", ex);
		}
	}

	private void unsubscribe(Session session) {
		String topic = getTopic(session);
		this.server.unsubscribe(topic, session.getBasicRemote());
	}

	@OnClose
	public void onClose(Session session, CloseReason closeReason) {
		logger.debug("client has closed the websocket: " + closeReason.getReasonPhrase());
		unsubscribe(session);
	}

	@OnError
	public void onError(Session session, Throwable thr) {
		if (thr instanceof SocketTimeoutException) {
			logger.warn("idle timeout, unsubscribe a pub-sub client " + ((AuthPrincipal)session.getUserPrincipal()).getRemoteAddress()); 
		} else {
			logger.error("websocket error", thr);
		}
		unsubscribe(session);    	
	}

	public void setServer(PubSubServer server) {
		this.server = server;
	}
}