package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.resource.AuthPrincipal;

/**
 * A stateless endpoint class for javax.websocket API. Gets the PubSubServer 
 * instance from the user properties to do most of the actual work. 
 * 
 * @author klemela
 */
public class PubSubEndpoint extends Endpoint {
	
	public static final Logger logger = LogManager.getLogger();
	
	public static final String TOPIC_KEY = "topic-name";
	
    @Override
    public void onOpen(final Session session, EndpointConfig config) {
    	    	
    	session.setMaxIdleTimeout(getServer(session).getIdleTimeout());
    	
    	// get topic from path params    	
    	String topic = getTopic(session);
    	
    	/*
    	 * topic authorization is checked twice: ones in the servlet filter
    	 * to make a client notice errors already in the connection
    	 * handshake and the second time in here, because the topic parsing
    	 * in the servlet filter is little bit worrying
    	 */
    	try {    		
    		boolean isAuthorized = getServer(session).isTopicAuthorized((AuthPrincipal)session.getUserPrincipal(), topic);
    		
    		if (!isAuthorized) {
    			close(session, CloseCodes.VIOLATED_POLICY, "access denied");
    			return;
    		}
    	} catch (Exception e) {
    		logger.error("error in topic authorization check", e);
    		close(session, CloseCodes.UNEXPECTED_CONDITION, "internal server error");
    		return;
    	}
    	
		// store topic to user properties, because we need it when we unsubscribe
		session.getUserProperties().put(TOPIC_KEY, topic);

		// subscribe for server messages
		
		Subscriber subscriber = new Subscriber(
				session.getBasicRemote(), 
				((AuthPrincipal)session.getUserPrincipal()).getRemoteAddress(),
				((AuthPrincipal)session.getUserPrincipal()).getDetails(),
				session.getUserPrincipal().getName());
				
		getServer(session).subscribe(topic, subscriber);

		// listen for client replies
		MessageHandler messageHandler = getServer(session).getMessageHandler();
		if (messageHandler != null) {
			session.addMessageHandler(messageHandler);
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

	private PubSubServer getServer(Session session) {
		return (PubSubServer) session.getUserProperties().get(PubSubServer.class.getName());
	}

	private void unsubscribe(Session session) {
		String topic = getTopic(session);
    	getServer(session).unsubscribe(topic, session.getBasicRemote());
	}

	@Override
    public void onClose(Session session, CloseReason closeReason) {
		logger.debug("client has closed the websocket: " + closeReason.getReasonPhrase());
		unsubscribe(session);
    }
    
    @Override
    public void onError(Session session, Throwable thr) {
    	if (thr instanceof SocketTimeoutException) {
    		logger.warn("idle timeout, unsubscribe a pub-sub client " + ((AuthPrincipal)session.getUserPrincipal()).getRemoteAddress()); 
    	} else {
    		logger.error("websocket error", thr);
    	}
    	unsubscribe(session);    	
    }
}