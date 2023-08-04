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
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;

/**
 * A endpoint class for javax.websocket API. Assumes the PubSubConfigurator has set the PubSubServer 
 * instance which will do the most of the actual work. 
 * 
 * @author klemela
 */

@ClientEndpoint
@ServerEndpoint(value = "/")
public class PubSubEndpoint {

	public static final Logger logger = LogManager.getLogger();

	public static final String TOPIC_KEY = "topic";

	private PubSubServer server;

	@OnOpen
	public void onOpen(final Session session, EndpointConfig config) {

		session.setMaxIdleTimeout(this.server.getIdleTimeout());

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
				/* 
				 * Throwing an exception allows a clear way to interrupt execution of this method
				 * before the connnection is subscribed to get any real content.
				 * 
				 * session.close(); return; would work too, but it would be too easy forget the
				 * return clause. 
				 */
				throw new WebSocketClosedException(CloseReason.CloseCodes.VIOLATED_POLICY, "no token in request");
			}
			
			String tokenKey = tokenParameters.get(0);

			if (tokenKey == null) {
				logger.debug("no token");
				throw new WebSocketClosedException(CloseReason.CloseCodes.VIOLATED_POLICY, "no token in request");
			}

			// doesn't have to be a Principal anymore, because it's not passed in ServletRequest, ValidToken would enough
			AuthPrincipal principal = null;

			try {
				principal = server.getTopicConfig().getUserPrincipal(tokenKey);
				
			} catch (NotFoundException e) {
				throw new WebSocketClosedException(CloseReason.CloseCodes.VIOLATED_POLICY, "not found: " + e.getMessage());
				
			} catch (ForbiddenException e) {
				throw new WebSocketClosedException(CloseReason.CloseCodes.VIOLATED_POLICY, "forbidden: " + e.getMessage());
				
			} catch (jakarta.ws.rs.NotAuthorizedException e) {
				throw new WebSocketClosedException(CloseReason.CloseCodes.VIOLATED_POLICY, "not authorized: " + e.getMessage());
				
			} catch (Exception e) {
				logger.error("error in websocket authentication", e);
				throw new WebSocketClosedException(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "internal server error");
			}

			if (principal == null) {
				throw new WebSocketClosedException(CloseReason.CloseCodes.VIOLATED_POLICY, "access denied");
			}

			// get topic
			List<String> topics = requestParameters.get(TOPIC_KEY);
			String topic = null;
			
			if (topics != null && topics.size() == 1) {
			    topic = decodeTopic(topics.get(0));
			}

			boolean isAuthorized = this.server.isTopicAuthorized(principal, topic);

			if (!isAuthorized) {
				throw new WebSocketClosedException(CloseReason.CloseCodes.VIOLATED_POLICY, "token not accepted");
			}

			// authentication ok
			logger.debug("authentication ok");

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

		} catch (WebSocketClosedException e) {
			try {
				session.close(e.getCloseReason());
			} catch (IOException e2) {
				logger.warn("websocket close failed", e2);
			}
		}
	}

	public static String decodeTopic(String topic) {
		if (topic == null) {
			return null;
		}
		// the topic is url encoded to allow slash characters
		try {
			String decoded = URLDecoder.decode(topic, StandardCharsets.UTF_8.toString());
			return decoded;

		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException("topic decode failed", e);
		}
	}

	private void unsubscribe(Session session) {
		String topic = (String) session.getUserProperties().get(TOPIC_KEY);
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