package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.ws.rs.core.UriBuilder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketClosedException;
import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketErrorException;

public class WebSocketClientEndpoint extends Endpoint {
	
	private static final Logger logger = LogManager.getLogger();
	private String uri;
	private String name;
	private MessageHandler messageHandler;
	private CountDownLatch disconnectLatch;
	private CountDownLatch connectLatch = new CountDownLatch(1);
	private CloseReason closeReason;
	private Throwable throwable;
	private Session session;
	private RetryHandler retryHandler;
	
	public WebSocketClientEndpoint(String uri, String name, MessageHandler.Whole<String> messageHandler, RetryHandler retryHandler) {
		this.uri = uri;
		this.name = name;
		this.messageHandler = messageHandler;
		this.retryHandler = retryHandler;
	}

	@Override
	public void onOpen(Session session, EndpointConfig config) {
		// hide query parameters (including a token) from logs 
		String uriWithoutParams = UriBuilder.fromUri(uri).replaceQuery(null).toString();
		
		this.session = session;

		logger.info("websocket client " + name + " connected succesfully: " + uriWithoutParams);
		if (messageHandler != null) {
			session.addMessageHandler(messageHandler);
		}
		
		disconnectLatch = new CountDownLatch(1);
		connectLatch.countDown();
		// reset the retry counter after a successful reconnection
		if (retryHandler != null) {
			retryHandler.reset();
		}
	}							

	@Override
	public void onClose(Session session, CloseReason reason) {

		logger.info("websocket client " + name + " closed: " + reason.getReasonPhrase());

		closeReason = reason;
		connectLatch.countDown();
		disconnectLatch.countDown();
    }

	@Override
    public void onError(Session session, Throwable thr) {
		logger.info("websocket client " + name + " error: " + thr.getMessage(), thr);
		throwable = thr;
		connectLatch.countDown();
		disconnectLatch.countDown();
    }

	public void close() throws IOException {
		session.close(new CloseReason(CloseCodes.NORMAL_CLOSURE, "client closing"));
		try {
			if (!disconnectLatch.await(1, TimeUnit.SECONDS)) {
				logger.warn("failed to close the websocket client " + name);
			}
		} catch (InterruptedException e) {
			logger.warn("failed to close the websocket client " + name, e);
		}
	}

	public void waitForConnection() throws InterruptedException, WebSocketClosedException, WebSocketErrorException {
		connectLatch.await();
		
		if (closeReason != null) {
			throw new WebSocketClosedException(closeReason);
		} else if (throwable != null) {
			throw new WebSocketErrorException(throwable);
		}
	}

	public void sendText(String text) throws IOException {
		session.getBasicRemote().sendText(text);
	}

	public void ping() throws IllegalArgumentException, IOException, TimeoutException, InterruptedException {
		logger.debug("ping");
		PongHandler pongHandler = new PongHandler();
		session.addMessageHandler(pongHandler);
		session.getBasicRemote().sendPing(null);
		pongHandler.await();
		session.removeMessageHandler(pongHandler);
	}
	
	public static class PongHandler implements MessageHandler.Whole<PongMessage> {
		private CountDownLatch latch = new CountDownLatch(1);
		@Override
		public void onMessage(PongMessage message) {
			logger.debug("pong");
			latch.countDown();
		}
		public void await() throws TimeoutException, InterruptedException {
			boolean received = latch.await(2, TimeUnit.SECONDS);
			if (!received) {
				throw new TimeoutException("timeout while waiting for pong message");
			}
		}
	}
}
