package fi.csc.chipster.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.tyrus.client.ClientManager;

public class WebSocketSourceEndpoint extends Endpoint {
	
	public static final Logger logger = LogManager.getLogger();
	
	private WebSocketTargetEndpoint targetEndpoint;
	private Session sourceSession;
		
    @Override
    public void onOpen(final Session sourceSession, EndpointConfig config) {
    	
    	this.sourceSession = sourceSession;

    	String targetUri = getTargetUri(sourceSession, config);    	        	
    	logger.debug("proxy " + sourceSession.getRequestURI() + " \t -> " + targetUri);
    	
   		connectToTarget(targetUri);					
    }       

	private void connectToTarget(String targetUri) {
		try {
			CountDownLatch connectLatch = new CountDownLatch(1);

			this.targetEndpoint = new WebSocketTargetEndpoint(this, connectLatch, targetUri);

			ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().build();
			ClientManager client = ClientManager.createClient();
			client.connectToServer(targetEndpoint, cec, new URI(targetUri));

			connectLatch.await();

			sourceSession.addMessageHandler(new MessageHandler.Whole<String>() {
				@Override
				public void onMessage(String message) {
					targetEndpoint.sendText(message);
				}			
			});

		} catch (DeploymentException e) {
			// authentication error or bad request, no need to log
			closeSource(ProxyServer.toCloseReason(e));
		} catch (IOException | URISyntaxException | InterruptedException e) {
			logger.error("failed to connect to " + targetUri, e);
			closeSource(ProxyServer.toCloseReason(e));
		}
	}

	private String getTargetUri(Session sourceSession, EndpointConfig config) {
		
    	String targetUri = (String) config.getUserProperties().get(ProxyServer.PROXY_TO);
    	String prefix = (String) config.getUserProperties().get(ProxyServer.PREFIX);    
    	
    	String path = sourceSession.getRequestURI().getPath().toString();
    	if (!path.startsWith(prefix)) {
    		throw new IllegalArgumentException("path " + path + " doesn't start with prefix " + prefix);
    	}
    	
    	targetUri += path.replaceFirst(prefix, "");
    	targetUri += "?" + sourceSession.getQueryString();
    	
    	return targetUri;
	}

	@Override
    public void onClose(Session session, CloseReason closeReason) {
		targetEndpoint.closeTarget(closeReason);
    }
    
    @Override
    public void onError(Session session, Throwable thr) {
    	targetEndpoint.closeTarget(ProxyServer.toCloseReason(thr));
    }
    
	public void closeSource(CloseReason closeReason) {
		try {
			sourceSession.close(closeReason);
		} catch (IOException e) {
			logger.error("failed to close the source websocket", e);
		}
	}

	public void sendText(String message) {
		try {
			sourceSession.getBasicRemote().sendText(message);
		} catch (IOException e) {
			logger.error("failed to send a message", e);
			targetEndpoint.closeTarget(ProxyServer.toCloseReason(e));
			closeSource(ProxyServer.toCloseReason(e));
		}
	}
}