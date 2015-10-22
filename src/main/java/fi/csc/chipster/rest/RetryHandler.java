package fi.csc.chipster.rest;

import javax.websocket.CloseReason;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.tyrus.client.ClientManager;

public class RetryHandler extends ClientManager.ReconnectHandler {

	private static final Logger logger = LogManager.getLogger();

	private int counter = 0;
	private int retries = 30;

	@Override
	public boolean onDisconnect(CloseReason closeReason) {		
		counter++;
		if (counter <= retries) {
			logger.info("websocket disconnected: " + closeReason.getReasonPhrase() + " Reconnecting... (" + counter + "/" + retries + ")");
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean onConnectFailure(Exception exception) {
		counter++;
		if (counter <= retries) {
			logger.info("websocket connection failure: " + exception.getMessage() + " Reconnecting... (" + counter + "/" + retries + ")");
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
}