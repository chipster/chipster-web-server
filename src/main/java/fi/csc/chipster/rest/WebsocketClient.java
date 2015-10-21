package fi.csc.chipster.rest;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler.Whole;
import javax.websocket.Session;
import javax.ws.rs.core.UriBuilder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;

public class WebsocketClient {
	
	public static final Logger logger = LogManager.getLogger();
	
	private CountDownLatch latch = new CountDownLatch(1);

	private ClientManager client;

	private Whole<String> messageHandler;
	private Session session;
	
	public WebsocketClient() {		
	}
	
	public WebsocketClient(final String uri, final Whole<String> messageHandler) throws DeploymentException, IOException, URISyntaxException, InterruptedException {
		connect(uri, messageHandler, true);
	}
	
	public WebsocketClient(final String uri, final Whole<String> messageHandler, boolean retry) throws DeploymentException, IOException, URISyntaxException, InterruptedException {
		connect(uri, messageHandler, retry);
	}

	public void connect(final String uri, final Whole<String> messageHandler) throws DeploymentException, IOException, URISyntaxException, InterruptedException {
		connect(uri, messageHandler, true);
	}
	public void connect(final String uri, final Whole<String> messageHandler, boolean retry) throws DeploymentException, IOException, URISyntaxException, InterruptedException {
		this.messageHandler = messageHandler;
		
		final ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().build();
		client = ClientManager.createClient();
		if (retry) {
			client.getProperties().put(ClientProperties.RECONNECT_HANDLER, new RetryHandler());
		}
		// HTTP Basic authentication
		//client.getProperties().put(ClientProperties.CREDENTIALS, new Credentials("ws_user", "password"));

		client.connectToServer(new Endpoint() {

			@Override
			public void onOpen(javax.websocket.Session session, EndpointConfig config) {
				// hide query parameters (including a token) from logs 
				String uriWithoutParams = UriBuilder.fromUri(uri).replaceQuery(null).toString();
				logger.info("websocket connected succesfully: " + uriWithoutParams);
				if (messageHandler != null) {
					session.addMessageHandler(messageHandler);
				}
				WebsocketClient.this.session = session;
				latch.countDown();
			}		
		}, cec, new URI(uri));
		latch.await();
	}

	public void sendText(String text) throws InterruptedException, IOException {
		session.getBasicRemote().sendText(text);
	}
	
	public void shutdown() {
		session.removeMessageHandler(messageHandler);
		client.shutdown();
	}
}