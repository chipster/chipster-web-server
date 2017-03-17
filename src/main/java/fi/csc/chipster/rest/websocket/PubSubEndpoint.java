package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.net.SocketTimeoutException;

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
    	    	
    	//session.setMaxIdleTimeout(0);
    	
    	// get topic from path params    	
    	String topic = session.getPathParameters().get(TOPIC_KEY);
    	
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
		getServer(session).subscribe(topic, new PubSubServer.Subscriber(session.getBasicRemote(), getRemoteAddress(session)));

		// listen for client replies
		MessageHandler messageHandler = getServer(session).getMessageHandler();
		if (messageHandler != null) {
			session.addMessageHandler(messageHandler);
		}
    }
    
    private String getRemoteAddress(Session session) {
    	// jetty seems to offer remote address, which is convenient for debug prints
    	return session.getUserProperties().get("javax.websocket.endpoint.remoteAddress").toString();
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
		String topic = (String) session.getUserProperties().get(TOPIC_KEY);
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
    		logger.warn("idle timeout, unsubscribe a pub-sub client " + getRemoteAddress(session)); 
    	} else {
    		logger.error("websocket error", thr);
    	}
    	unsubscribe(session);    	
    }
}