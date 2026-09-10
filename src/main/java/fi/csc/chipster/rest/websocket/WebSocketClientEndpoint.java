package fi.csc.chipster.rest.websocket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;

/**
 * Endpoint of the WebSocketClient
 *
 * Registers the message handler and reports disconnects to the client. All
 * connecting and reconnecting happens in the client's own thread, so these
 * callbacks only have to hand over what they know and return.
 */
public class WebSocketClientEndpoint extends Endpoint {

	public static interface EndpointListener {
		public void onClose(CloseReason reason);

		public void onError(Throwable thr);
	}

	private static final Logger logger = LogManager.getLogger();

	private final MessageHandler.Whole<String> messageHandler;
	private final EndpointListener endpointListener;

	// written on a container thread, read by the reconnect thread
	private volatile CloseReason closeReason;

	public WebSocketClientEndpoint(MessageHandler.Whole<String> messageHandler, EndpointListener endpointListener) {
		this.messageHandler = messageHandler;
		this.endpointListener = endpointListener;
	}

	@Override
	public void onOpen(Session session, EndpointConfig config) {
		logger.debug("WebSocket client onOpen");

		if (messageHandler != null) {
			session.addMessageHandler(String.class, messageHandler);
		}
	}

	@Override
	public void onClose(Session session, CloseReason reason) {
		logger.debug("WebSocket client onClose: " + reason);

		closeReason = reason;
		endpointListener.onClose(reason);
	}

	@Override
	public void onError(Session session, Throwable thr) {
		logger.debug("WebSocket client onError: " + thr.getMessage());

		endpointListener.onError(thr);
	}

	/**
	 * Why the server closed this connection, or null if it hasn't closed it (or
	 * closed it without saying why). The client uses it to fail an attempt as
	 * soon as the server refuses it, and to say why in the exception. Every
	 * close code is retried - see RetryHandler.
	 */
	public CloseReason getCloseReason() {
		return closeReason;
	}
}
