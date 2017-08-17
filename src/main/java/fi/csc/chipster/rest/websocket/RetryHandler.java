package fi.csc.chipster.rest.websocket;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.DeploymentException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.core.HandshakeException;

public class RetryHandler extends ClientManager.ReconnectHandler {

	private static final Logger logger = LogManager.getLogger();

	private int counter = 0;
	private int retries = 30;

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
		
		logger.info("websocket client " + name + " disconnected: " + closeReason.getReasonPhrase());
		
		if (CloseCodes.VIOLATED_POLICY == closeReason.getCloseCode()) {
			logger.error("reconnection cancelled");
			throw new RuntimeException(closeReason.getReasonPhrase());
		}
		counter++;
		if (counter <= retries) {
			logger.info("reconnecting... (" + counter + "/" + retries + ")");
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean onConnectFailure(Exception exception) {
		logger.info("websocket client " + name + " connection failure", exception);
		
		if (exception instanceof DeploymentException && exception.getCause() instanceof HandshakeException) {
			logger.error("unrecoverable connection failure, reconnection cancelled");
			return false;
		}
		counter++;
		if (counter <= retries) {
			logger.info("reconnecting... (" + counter + "/" + retries + ")");
			// Thread.sleep(...) or something other "sleep-like" expression can be put here - you might want
			// to do it here to avoid potential DDoS when you don't limit number of reconnects.					
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