package fi.csc.chipster.scheduler;

import java.io.IOException;
import java.net.URI;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.MessageHandler;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;

public class PubSubServer {
	
	private static final Logger logger = LogManager.getLogger();
	
	ConcurrentHashMap<Basic, String> subsribers = new ConcurrentHashMap<>();

	private MessageHandler.Whole<String> replyHandler;

	private Server server;

	public PubSubServer(String baseUri, TokenServletFilter filter, MessageHandler.Whole<String> replyHandler) throws Exception {
		this.replyHandler = replyHandler;
        server = new Server();
        
        URI uri = URI.create(baseUri);
    	
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(uri.getHost());
        connector.setPort(uri.getPort());
        server.addConnector(connector);
        
        // Setup the basic application "context" for this application at "/"
        // This is also known as the handler tree (in jetty speak)
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       

        context.setContextPath("/");
        
        context.addFilter(new FilterHolder(filter), "/*", null);
        server.setHandler(context);
        		
        // Initialize javax.websocket layer
        ServerContainer wscontainer = WebSocketServerContainerInitializer.configureContext(context);

        ServerEndpointConfig serverConfig = ServerEndpointConfig.Builder.create(PubSubEndpoint.class, uri.getPath()).build();
        serverConfig.getUserProperties().put(this.getClass().getName(), this);
        // Add WebSocket endpoint to javax.websocket layer
        wscontainer.addEndpoint(serverConfig);             
	}

	public void publish(String msg) {
		// usage of the same remoteEndpoint isn't allowed from multiple endpoints
		// http://stackoverflow.com/questions/26264508/websocket-async-send-can-result-in-blocked-send-once-queue-filled
		synchronized (this) {			
			for (Entry<Basic, String> entry : subsribers.entrySet()) {
				Basic remote = entry.getKey();
				String address = entry.getValue();
				try {
					remote.sendText(msg);
				} catch (IOException e) {
					// nothing to worry about if the client just unsubscribed 
					logger.warn("failed to publish a message to: " + subsribers.get(address), e);
				}
			}
		}
	}

	public void subscribe(Basic basicRemote, String remoteAddress) {
		this.subsribers.put(basicRemote, remoteAddress);
	}

	public void unsubscribe(Basic basicRemote) {
		this.subsribers.remove(basicRemote);
	}

	public MessageHandler.Whole<String> getMessageHandler() {
		return this.replyHandler;
	}

	public Server getServer() {
		return this.server;
	}
}
