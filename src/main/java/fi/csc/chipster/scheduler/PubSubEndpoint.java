package fi.csc.chipster.scheduler;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
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
	
    @Override
    public void onOpen(final Session session, EndpointConfig config) {    
    	// subscribe for server messages
    	subscribe(session);
    	// listen for client replies
        session.addMessageHandler(getServer(session).getMessageHandler());
    }
    
    private void subscribe(Session session) {
    	String remoteAddress = session.getUserProperties().get("javax.websocket.endpoint.remoteAddress").toString();
    	getServer(session).subscribe(session.getBasicRemote(), remoteAddress);
	}
    
    private PubSubServer getServer(Session session) {
		return (PubSubServer) session.getUserProperties().get(PubSubServer.class.getName());
	}

	private void unsubscribe(Session session) {
    	getServer(session).unsubscribe(session.getBasicRemote());
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