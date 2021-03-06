package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Timer;
import java.util.concurrent.TimeoutException;

import jakarta.ws.rs.core.UriBuilder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.tyrus.client.ClientManager;

import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.websocket.WebSocketClientEndpoint.EndpointListener;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler.Whole;
import jakarta.websocket.Session;

public class WebSocketClient implements EndpointListener {
	
	public static final Logger logger = LogManager.getLogger();

	private String name;

	private ClientManager client;
	private WebSocketClientEndpoint endpoint;	
	private RetryHandler retryHandler;
	private Timer pingTimer = new Timer("ping timer", true);

	private String uri;

	private Whole<String> messageHandler;

	private CredentialsProvider credentials;	
	
	public WebSocketClient(final String uri, final Whole<String> messageHandler, boolean retry, final String name, CredentialsProvider credentials) throws InterruptedException, WebSocketErrorException, WebSocketClosedException {
	
		this.name = name;
		this.uri = uri;
		this.messageHandler = messageHandler;
		this.credentials = credentials;
		
		if (retry) {
			/* 
			 * Handle retries in this class instead of letting Tyrus to do it
			 * 
			 * Tyrus would try to reconnect always to the same URL, which won't work after the token has expired.
			 * 
			 * RetryHandler could be given for the Tyrus  like this: 
			 * client.getProperties().put(ClientProperties.RECONNECT_HANDLER, retryHandler); 
			 */
			this.retryHandler = new RetryHandler(name);
		}
		
		this.connect();
	}
	
	private void connect() throws WebSocketErrorException, InterruptedException, WebSocketClosedException {
		
		final ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().build();
		client = ClientManager.createClient();
		
		// HTTP Basic authentication
		//client.getProperties().put(ClientProperties.CREDENTIALS, new Credentials("ws_user", "password"));	

		try {
			
			UriBuilder uriBuilder = UriBuilder.fromUri(this.uri);
			
			if (credentials != null) {
				uriBuilder = uriBuilder.queryParam("token", credentials.getPassword().toString());
			}
			
			logger.info("websocket client " + name + " connecting to " + uri);
			
			endpoint = new WebSocketClientEndpoint(messageHandler, this);
			client.connectToServer(endpoint, cec, new URI(uriBuilder.toString()));
		} catch (DeploymentException | IOException | URISyntaxException e) {
			throw new WebSocketErrorException(e);
		}
		
		endpoint.waitForConnection();
	}	
	
	/*
	 * For reconnection tests
	 */
	public void waitForConnection() throws InterruptedException, WebSocketClosedException, WebSocketErrorException {
		if (this.endpoint != null) {
			this.endpoint.waitForConnection();
		} else {
			throw new IllegalStateException("not connected");
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
		try {
			if (!endpoint.waitForDisconnect(1)) {
				logger.warn("failed to close the websocket client " + name);
			}
		} catch (InterruptedException e) {
			logger.warn("failed to close the websocket client " + name, e);
		}		
		client.shutdown();
	}

	public void ping() throws IOException, TimeoutException, InterruptedException {
		endpoint.ping();
	}

	public void onOpen(Session session, EndpointConfig config) {
		logger.info("websocket client " + name + " connected succesfully: " + uri);
		if (retryHandler != null) {
			retryHandler.reset();
		}
	}

	public void onClose(Session session, CloseReason reason) {
		logger.info("websocket client " + name + " closed: " + reason.getReasonPhrase());
		if (retryHandler != null) {
			while (retryHandler.onDisconnect(reason)) {
				try {
					Thread.sleep(retryHandler.getDelay() * 1000);
					this.connect();
					break;
				} catch (WebSocketErrorException | InterruptedException | WebSocketClosedException e) {
					logger.error("error in reconnection", e);
				}
			}
		} 
	}

	public void onError(Session session, Throwable thr) {
		logger.warn("websocket client " + name + " error: " + thr.getMessage(), thr);
		if (retryHandler != null) {
			while (retryHandler.onConnectFailure((Exception) thr)) {
				try {
					Thread.sleep(retryHandler.getDelay() * 1000);
					this.connect();
					break;
				} catch (WebSocketErrorException | InterruptedException | WebSocketClosedException e) {
					logger.error("error in reconnection", e);
				}
			}
		}
	}
}