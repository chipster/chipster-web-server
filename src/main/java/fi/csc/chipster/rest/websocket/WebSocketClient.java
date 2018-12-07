package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Timer;
import java.util.concurrent.TimeoutException;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler.Whole;
import javax.websocket.Session;
import javax.ws.rs.core.UriBuilder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.tyrus.client.ClientManager;

import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.websocket.WebSocketClientEndpoint.EndpointListener;

public class WebSocketClient implements EndpointListener {
	
	public static final Logger logger = LogManager.getLogger();

//	private static final long PING_INTERVAL = 60_000;	

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
			
			logger.info("websocket client" + name + " connecting to " + uri);
			
			endpoint = new WebSocketClientEndpoint(messageHandler, this);
			client.connectToServer(endpoint, cec, new URI(uriBuilder.toString()));
		} catch (DeploymentException | IOException | URISyntaxException e) {
			throw new WebSocketErrorException(e);
		}
		
		endpoint.waitForConnection();
		 
		// prevent jetty from closing this connection if it is idle for 5 minutes
//		pingTimer.schedule(new TimerTask() {
//			@Override
//			public void run() {
//				try {
//					ping();
//				} catch (IOException | TimeoutException | InterruptedException e) {
//					logger.error("failed to send a ping", e);
//				}
//			}			
//		}, PING_INTERVAL, PING_INTERVAL);
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