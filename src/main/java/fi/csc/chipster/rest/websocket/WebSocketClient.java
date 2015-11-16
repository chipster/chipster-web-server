package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeoutException;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.MessageHandler.Whole;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;

public class WebSocketClient {
	
	public static final Logger logger = LogManager.getLogger();

	private static final long PING_INTERVAL = 60_000;	

	private String name;

	private ClientManager client;
	private WebSocketClientEndpoint endpoint;	
	private RetryHandler retryHandler;
	private Timer pingTimer = new Timer();	
	
	public WebSocketClient(final String uri, final Whole<String> messageHandler, boolean retry, final String name) throws InterruptedException, WebSocketErrorException, WebSocketClosedException {
	
		this.name = name;
		
		final ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().build();
		client = ClientManager.createClient();
		if (retry) {
			this.retryHandler = new RetryHandler(name);
			client.getProperties().put(ClientProperties.RECONNECT_HANDLER, retryHandler);
		}
		// HTTP Basic authentication
		//client.getProperties().put(ClientProperties.CREDENTIALS, new Credentials("ws_user", "password"));	

		try {
			endpoint = new WebSocketClientEndpoint(uri, name, messageHandler, retryHandler);
			client.connectToServer(endpoint, cec, new URI(uri));
		} catch (DeploymentException | IOException | URISyntaxException e) {
			throw new WebSocketErrorException(e);
		}
		
		endpoint.waitForConnection();
		 
		// prevent jetty from closing this connection if it is idle for 5 minutes  
		pingTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					ping();
				} catch (IOException | TimeoutException | InterruptedException e) {
					logger.error("failed to send a ping", e);
				}
			}			
		}, PING_INTERVAL, PING_INTERVAL);
	}	
	
	public static class WebSocketClosedException extends Exception {

		public WebSocketClosedException(CloseReason closeReason) {
			super(closeReason.getCloseCode() + closeReason.getReasonPhrase());
		}			
	}
	
	public static class WebSocketErrorException extends Exception {

		public WebSocketErrorException(Throwable throwable) {
			super(throwable);
		}
	}

	public void sendText(String text) throws InterruptedException, IOException {
		endpoint.sendText(text);
	}
	
	public void shutdown() throws IOException {		
		logger.debug("shutdown websocket client " + name);
		if (retryHandler != null) {
			retryHandler.close();
		}
		pingTimer.cancel();
		endpoint.close();		
		client.shutdown();
	}

	public void ping() throws IOException, TimeoutException, InterruptedException {
		endpoint.ping();
	}	
}