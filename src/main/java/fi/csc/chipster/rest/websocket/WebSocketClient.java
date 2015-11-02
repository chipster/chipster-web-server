package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeoutException;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.MessageHandler.Whole;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;

import fi.csc.chipster.rest.RetryHandler;

public class WebSocketClient {
	
	public static final Logger logger = LogManager.getLogger();	

	private String name;

	private ClientManager client;
	private WebSocketClientEndpoint endpoint;	
	private RetryHandler retryHandler;
	
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
			endpoint = new WebSocketClientEndpoint(uri, name, messageHandler);
			client.connectToServer(endpoint, cec, new URI(uri));
		} catch (DeploymentException | IOException | URISyntaxException e) {
			throw new WebSocketErrorException(e);
		}
		
		endpoint.waitForConnection();						
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
		endpoint.close();		
		client.shutdown();
	}

	public void ping() throws IOException, TimeoutException, InterruptedException {
		endpoint.ping();
	}	
}