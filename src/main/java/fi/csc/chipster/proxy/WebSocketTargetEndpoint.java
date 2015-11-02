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

public class WebSocketTargetEndpoint extends Endpoint {
	
	private static final Logger logger = LogManager.getLogger();
	
	private WebSocketSourceEndpoint sourceEndpoint;
	private Session targetSession;
	private String targetUri;
	private CountDownLatch connectLatch;
	
	public WebSocketTargetEndpoint(WebSocketSourceEndpoint sourceEndpoint, CountDownLatch openLatch, String targetUri) {
		this.sourceEndpoint = sourceEndpoint;
		this.connectLatch = openLatch;
		this.targetUri = targetUri;
	}

	@Override
	public void onOpen(Session targetSession, EndpointConfig config) {							
		this.targetSession = targetSession;
		
		targetSession.addMessageHandler(new MessageHandler.Whole<String>() {
			@Override
			public void onMessage(String message) {
				sourceEndpoint.sendText(message);
			}			
		});
		connectLatch.countDown();
	}							

	@Override
	public void onClose(Session session, CloseReason reason) {
		connectLatch.countDown();
		sourceEndpoint.closeSource(reason);
    }

	@Override
    public void onError(Session session, Throwable thr) {
		connectLatch.countDown();
		sourceEndpoint.closeSource(ProxyServer.toCloseReason(thr));
    }
	

	public void sendText(String message) {
		try {
			targetSession.getBasicRemote().sendText(message);
		} catch (IOException e) {
			logger.error("failed to send a message to " + targetUri, e);
			sourceEndpoint.closeSource(ProxyServer.toCloseReason(e));
			closeTarget(ProxyServer.toCloseReason(e));
		}
	}

	public void closeTarget(CloseReason closeReason) {
		try {
			if (targetSession != null) {
				targetSession.close(closeReason);
			}
		} catch (IOException e) {
			logger.error("failed to close the target websocket to " + targetUri, e);
		}
	}
}
