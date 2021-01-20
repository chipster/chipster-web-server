package fi.csc.chipster.rest.websocket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.core.HandshakeException;

import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.DeploymentException;

public class RetryHandler extends ClientManager.ReconnectHandler {

	private static final Logger logger = LogManager.getLogger();

	private int counter = 0;
	private int retries = -1;

	private String name;

	private volatile boolean close = false;

	public RetryHandler(String name) {
		this.name = name;
	}

	@Override
	public boolean onDisconnect(CloseReason closeReason) {
		if (close) {
			// don't reconnect when we are trying to close the connection on purpose
			return false;
		}
		
		if (CloseCodes.VIOLATED_POLICY == closeReason.getCloseCode()) {
			logger.error("reconnection cancelled");
			throw new RuntimeException(new WebSocketClosedException(closeReason));
		}
		counter++;
		if (retries < 0 || counter <= retries) {
			logger.info("reconnecting... (" + counter + "/" + retries + ")");
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean onConnectFailure(Exception exception) {
		logger.info("websocket client " + name + " connection failure", exception);
		
		// HTTP upgrade request errors
		if (exception instanceof DeploymentException && exception.getCause() instanceof HandshakeException) {
			logger.error("unrecoverable connection failure, reconnection cancelled");
			return false;
		}
		
		// VIOLATE_POLICY from onDisconnect(). Should we check the close reason also here?
		if (exception instanceof RuntimeException && exception.getCause() instanceof WebSocketClosedException) {
			logger.error("unrecoverable websocket close, reconnection cancelled");
			return false;
		}
				
		counter++;
		if (retries < 0 || counter <= retries) {
			logger.info("reconnecting... (" + counter + "/" + retries + ")");					
			return true;
		} else {
			return false;
		}
	}

	@Override
	public long getDelay() {		
		if (counter < 1) {			
			return 0;
		} else if (counter < 15) {
			return 1;
		} else if (counter < 30) {
			return 10;
		} else {
			return 60;
		}				
	}

	public void close() {
		this.close = true;
	}

	public void reset() {
		counter = 0;
	}
}