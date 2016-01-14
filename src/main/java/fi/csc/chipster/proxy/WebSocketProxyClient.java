package fi.csc.chipster.proxy;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Client side of the websocket proxy
 * 
 * Based on the Java WebSocket standard JSR 356.
 * 
 * @author klemela
 *
 */
public class WebSocketProxyClient extends Endpoint {
	
	private static final Logger logger = LogManager.getLogger();
	
	private WebSocketProxySocket proxySocket;
	private Session clientSession;
	private String targetUri;
	private CountDownLatch connectLatch;
	
	public WebSocketProxyClient(WebSocketProxySocket jettyWebSocketSourceEndpoint, CountDownLatch openLatch, String targetUri) {
		this.proxySocket = jettyWebSocketSourceEndpoint;
		this.connectLatch = openLatch;
		this.targetUri = targetUri;
	}

	@Override
	public void onOpen(Session targetSession, EndpointConfig config) {							
		this.clientSession = targetSession;
		
		targetSession.addMessageHandler(new MessageHandler.Whole<String>() {
			@Override
			public void onMessage(String message) {
				proxySocket.sendText(message);
			}			
		});
		connectLatch.countDown();
	}							

	@Override
	public void onClose(Session session, CloseReason reason) {
		connectLatch.countDown();
		proxySocket.closeSocketSession(reason);
    }

	@Override
    public void onError(Session session, Throwable thr) {
		connectLatch.countDown();
		proxySocket.closeSocketSession(WebSocketProxyServlet.toCloseReason(thr));
    }
	

	public void sendText(String message) {
		try {
			clientSession.getBasicRemote().sendText(message);
		} catch (IOException e) {
			logger.error("failed to send a message to " + targetUri, e);
			proxySocket.closeSocketSession(WebSocketProxyServlet.toCloseReason(e));
			closeClientSession(WebSocketProxyServlet.toCloseReason(e));
		}
	}

	public void closeClientSession(CloseReason closeReason) {
		try {
			if (clientSession != null) {
				clientSession.close(closeReason);
			}
		} catch (IOException e) {
			logger.error("failed to close the target websocket to " + targetUri, e);
		}
	}
}
