package fi.csc.chipster.scheduler;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    	// get topic from path params (null if this endpoint was deployed without a path parameter)
    	String topicName = session.getPathParameters().get(TOPIC_KEY);
    	
    	// store topic to user properties, because we need it when unsubscribing
    	session.getUserProperties().put(TOPIC_KEY, topicName);
    	
    	// jetty seems to offer remote address, which is convenient for debug prints
    	String remoteAddress = session.getUserProperties().get("javax.websocket.endpoint.remoteAddress").toString();

    	// subscribe for server messages
    	getServer(session).subscribe(topicName, new PubSubServer.Subscriber(session.getBasicRemote(), remoteAddress));
    	
    	// listen for client replies
    	MessageHandler messageHandler = getServer(session).getMessageHandler();
    	if (messageHandler != null) {
    		session.addMessageHandler(messageHandler);
    	}
    }
    
    private PubSubServer getServer(Session session) {
		return (PubSubServer) session.getUserProperties().get(PubSubServer.class.getName());
	}

	private void unsubscribe(Session session) {
		String topicName = (String) session.getUserProperties().get(TOPIC_KEY);
    	getServer(session).unsubscribe(topicName, session.getBasicRemote());
	}

	@Override
    public void onClose(Session session, CloseReason closeReason) {
		logger.debug("client has closed the websocket: " + closeReason.getReasonPhrase());
		unsubscribe(session);
    }
    
    @Override
    public void onError(Session session, Throwable thr) {
    	logger.error("websocket error", thr);
    	unsubscribe(session);
    }
}