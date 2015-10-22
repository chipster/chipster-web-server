package fi.csc.chipster.scheduler;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletException;
import javax.websocket.DeploymentException;
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

import fi.csc.chipster.rest.RestUtils;

public class PubSubServer {
	
	private static final Logger logger = LogManager.getLogger();
	
	public static final String DEFAULT_TOPIC = "default-topic";
	
	public static class Subscriber {
		private Basic remote;
		private String remoteAddress;
		
		public Subscriber(Basic remote, String remoteAddress) {
			this.remote = remote;
			this.remoteAddress = remoteAddress;
		}
		
		public Basic getRemote() {
			return remote;
		}
		public void setRemote(Basic remote) {
			this.remote = remote;
		}
		public String getRemoteAddress() {
			return remoteAddress;
		}
		public void setRemoteAddress(String remoteAddress) {
			this.remoteAddress = remoteAddress;
		}
	}
	
	public static class Topic {
		private ConcurrentHashMap<Basic, Subscriber> subscribers = new ConcurrentHashMap<>();
		
		public void add(Subscriber s) {
			subscribers.put(s.remote, s);
			logger.debug("subscribers: " + subscribers.size());
		}

		public void remove(Basic basicRemote) {
			subscribers.remove(basicRemote);
		}

		public boolean isEmpty() {
			return subscribers.isEmpty();
		}

		public void publish(String msg) {
			// usage of the same remoteEndpoint isn't allowed from multiple endpoints
			// http://stackoverflow.com/questions/26264508/websocket-async-send-can-result-in-blocked-send-once-queue-filled
			synchronized (this) {
				logger.debug("publish to " + subscribers.size() + " subscribers: " + msg);
				for (Subscriber s: subscribers.values()) {
					try {
						logger.debug("send to " + s.remoteAddress);
						s.getRemote().sendText(msg);
					} catch (IOException e) {
						// nothing to worry about if the client just unsubscribed 
						logger.warn("failed to publish a message to: " + subscribers.get(s.getRemoteAddress()), e);
					}
				}
			}
		}
	}
	
	ConcurrentHashMap<String, Topic> topics = new ConcurrentHashMap<>();

	private MessageHandler.Whole<String> replyHandler;

	private Server server;

	private String baseUri;

	private String path;

	private TokenServletFilter filter;

	public PubSubServer(String baseUri, String path, TokenServletFilter filter, MessageHandler.Whole<String> replyHandler) throws ServletException, DeploymentException {
		this.baseUri = baseUri;
		this.path = path;
		this.filter = filter;
		this.replyHandler = replyHandler;
		
		init();                    
	}
	
	public void init() throws DeploymentException, ServletException {
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
        if (filter != null) {
        	context.addFilter(new FilterHolder(filter), "/*", null);
        }
        server.setHandler(context);
        		
        // Initialize javax.websocket layer
        ServerContainer wscontainer = WebSocketServerContainerInitializer.configureContext(context);

        ServerEndpointConfig serverConfig = ServerEndpointConfig.Builder.create(PubSubEndpoint.class, uri.getPath() + path).build();
        serverConfig.getUserProperties().put(this.getClass().getName(), this);
        // Add WebSocket endpoint to javax.websocket layer
        wscontainer.addEndpoint(serverConfig);
	}

	public void publish(Object obj) {
		publish(DEFAULT_TOPIC, RestUtils.asJson(obj));
	}

	public void publish(String topicName, Object obj) {
		publish(topicName, RestUtils.asJson(obj));
	}

	private void publish(String topicName, String msg) {
		
		Topic topic = topics.get(topicName);
		if (topic != null) {
			topic.publish(msg);
		} else {
			logger.debug("no one listening on topic: " + topicName);
		}
	}

	public void subscribe(String topicName, Subscriber s) {
		if (topicName == null) {
			topicName = DEFAULT_TOPIC;
		}
		synchronized (topics) {
			if (!topics.containsKey(topicName)) {
				logger.debug("topic " + topicName + " not found, create it");
				topics.put(topicName, new Topic());
			}
			Topic topic = topics.get(topicName);
			topic.add(s);
		}
	}

	public void unsubscribe(String topicName, Basic basicRemote) {
		if (topicName == null) {
			topicName = DEFAULT_TOPIC;
		}
		synchronized (topics) {
			Topic topic = topics.get(topicName);
			topic.remove(basicRemote);
			if (topic.isEmpty()) {
				logger.debug("topic " + topicName + " is empty, remove it");
				topics.remove(topicName);
			}
		}
	}

	public MessageHandler.Whole<String> getMessageHandler() {
		return this.replyHandler;
	}

	public void stop() {
		try {
			this.server.stop();
		} catch (Exception e) {
			logger.warn("failed to stop PubSubServer", e);
		}
	}

	public void start() {
		try {
			this.server.start();
		} catch (Exception e) {
			logger.error("failed to start PubSubServer", e);
		}
	}
}
