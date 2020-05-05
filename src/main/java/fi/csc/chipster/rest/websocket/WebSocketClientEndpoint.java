package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketClosedException;
import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketErrorException;
import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;

public class WebSocketClientEndpoint extends Endpoint {
	
	public static interface EndpointListener {
		public void onOpen(Session session, EndpointConfig config);
		public void onClose(Session session, CloseReason reason);
		public void onError(Session session, Throwable thr);		
	}
	
	private static final Logger logger = LogManager.getLogger();
	
	private MessageHandler messageHandler;
	private CountDownLatch disconnectLatch;
	private CountDownLatch connectLatch = new CountDownLatch(1);
	private CloseReason closeReason;
	private Throwable throwable;
	private Session session;
	private EndpointListener endpointListener;
	
	public WebSocketClientEndpoint(MessageHandler.Whole<String> messageHandler, EndpointListener endpointListener) {
		this.messageHandler = messageHandler;
		this.endpointListener = endpointListener;
	}

	@Override
	public void onOpen(Session session, EndpointConfig config) {
		
		this.session = session;
		
		if (messageHandler != null) {
			session.addMessageHandler(messageHandler);
		}
		
		disconnectLatch = new CountDownLatch(1);
		connectLatch.countDown();
		
		this.endpointListener.onOpen(session, config);
	}							

	@Override
	public void onClose(Session session, CloseReason reason) {		
		closeReason = reason;
		connectLatch.countDown();
		disconnectLatch.countDown();
		
		this.endpointListener.onClose(session, reason);
    }

	@Override
    public void onError(Session session, Throwable thr) {		
		throwable = thr;
		connectLatch.countDown();
		disconnectLatch.countDown();
		
		this.endpointListener.onError(session, thr);
    }

	public void close() throws IOException {
		session.close(new CloseReason(CloseCodes.NORMAL_CLOSURE, "client closing"));		
	}
	
	public boolean waitForDisconnect(long timeout) throws InterruptedException {
		return disconnectLatch.await(timeout, TimeUnit.SECONDS);
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
