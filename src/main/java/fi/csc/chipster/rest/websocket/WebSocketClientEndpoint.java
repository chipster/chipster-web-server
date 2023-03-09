package fi.csc.chipster.rest.websocket;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
		
		logger.debug("WebSocket client onOpen");
		
		this.session = session;
		
		if (messageHandler != null) {
			session.addMessageHandler(messageHandler);
		}
		
		disconnectLatch = new CountDownLatch(1);
		
		
		/* Wait for connection or error
		 * 
		 * If the server would use HTTP errors for signaling e.g. authentication errors, at this point
		 * we would already know that the connection was successful. Unfortunately JSR 356 Java API 
		 * for WebSocket doesn't support servlet filters or other methods
		 * for responding with HTTP errors to the original WebSocket upgrade request.
		 * 
		 * The server will check the authentication in the onOpen() method and close the connection if 
		 * the authentication fails. We'll know that the authentication failed when the onClose() method
		 * is called here in the client. The problem is to know when the authentication was
		 * accepted. This is solved by sending a ping. If we get the ping reply, we know that authentication 
		 * was accepted.
		 * 
		 * We have to wait for the ping reply in another thread, blocking this onOpen() method seems to stop also
		 * the ping or it's reply (understandably).
		 * 
		 * Side note: this logic is not critical for the information security, that has to be taken care in the server
		 * side. However, this is critical for reliable tests and for the server's to notice when their connection
		 * to other services fail.
		 */
		new Thread(new Runnable() {

			@Override
			public void run() {
				try {
					ping();
					connectLatch.countDown();
					
					endpointListener.onOpen(session, config);
					
				} catch (IllegalArgumentException | IOException | TimeoutException | InterruptedException e) {
					logger.warn("WebSocket client error", e);
				}
			}
			
		}, "websocket-connection-ping").start();		
	}							

    @Override
	public void onClose(Session session, CloseReason reason) {
		
		logger.debug("WebSocket client onClose: " + reason);
		
		closeReason = reason;
		connectLatch.countDown();
		disconnectLatch.countDown();
		
		this.endpointListener.onClose(session, reason);
    }

	@Override
    public void onError(Session session, Throwable thr) {
		
		logger.debug("WebSocket client onError: " + thr.getMessage());
		
		throwable = thr;
		connectLatch.countDown();
		disconnectLatch.countDown();
		
		this.endpointListener.onError(session, thr);
    }

	public void close() throws IOException {
		session.close(new CloseReason(CloseCodes.NORMAL_CLOSURE, "client closing"));		
	}
	
	public boolean waitForDisconnect(long timeout) throws InterruptedException {
		logger.debug("WebSocket client will wait for disconnect max " + timeout + " seconds");
		return disconnectLatch.await(timeout, TimeUnit.SECONDS);
	}

	public void waitForConnection() throws InterruptedException, WebSocketClosedException, WebSocketErrorException {
		logger.debug("WebSocket client waiting for connection " + closeReason);
		connectLatch.await();
		
		if (closeReason != null) {
			throw new WebSocketClosedException(closeReason);

		} else if (throwable != null) {
			
			// most likely error in the HTTP upgrade request, probably happens
			// only if the configuration or network is broken
			throw new WebSocketErrorException(throwable);
		}
	}

	public void sendText(String text) throws IOException {
		session.getBasicRemote().sendText(text);
	}

	public void ping() throws IllegalArgumentException, IOException, TimeoutException, InterruptedException {
		logger.debug("WebSocket client sends ping");
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
			logger.debug("WebSocket client received pong");
			latch.countDown();
		}
		
		public void await() throws TimeoutException, InterruptedException {
			int timeout = 2;
			logger.debug("WebSocket client will wait for pong max " + timeout + " seconds");
			boolean received = latch.await(timeout, TimeUnit.SECONDS);
			if (!received) {
				throw new TimeoutException("timeout while waiting for pong message");
			}
		}
	}
}
