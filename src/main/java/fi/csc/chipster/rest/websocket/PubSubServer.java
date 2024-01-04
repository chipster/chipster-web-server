package fi.csc.chipster.rest.websocket;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;

import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint.Basic;
import jakarta.websocket.server.ServerEndpointConfig;
import jakarta.ws.rs.NotAuthorizedException;

public class PubSubServer implements StatusSource {

	private static final Logger logger = LogManager.getLogger();

	public static final String DEFAULT_TOPIC = "default-topic";

	ConcurrentHashMap<String, Topic> topics = new ConcurrentHashMap<>();

	private MessageHandler.Whole<String> replyHandler;

	private Server server;

	private String baseUri;

	private TopicConfig topicConfig;

	private String name;

	private int messagesDiscarded;
	private int messagesReceived;
	private int messagesSent;
	private int subsribeCount;

	private int bytesReceived;

	private int bytesSent;

	private long idleTimeout = 0;

	private Timer pingTimer;
	private long pingInterval = 0;

	public PubSubServer(String baseUri, MessageHandler.Whole<String> replyHandler, TopicConfig topicCheck, String name)
			throws ServletException {
		this.baseUri = baseUri;
		this.replyHandler = replyHandler;
		this.topicConfig = topicCheck;
		this.name = name;

		init();
	}

	public void init() throws ServletException {
		server = new Server();
		RestUtils.configureJettyThreads(server, name);

		URI uri = URI.create(baseUri);

		ServerConnector connector = new ServerConnector(server);
		connector.setHost(uri.getHost());
		connector.setPort(uri.getPort());
		server.addConnector(connector);

		// Setup the basic application "context" for this application at "/"
		// This is also known as the handler tree (in jetty speak)
		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);

		String contextPath = uri.getPath().replaceAll("/$", "");
		if (contextPath.isEmpty()) {
			contextPath = "/";
		}
		logger.debug("context path " + contextPath);
		context.setContextPath(contextPath);

		PubSubNotFoundServletFilter filter = new PubSubNotFoundServletFilter();
		context.addFilter(new FilterHolder(filter), "/*", EnumSet.of(DispatcherType.REQUEST));

		server.setHandler(context);

		// Initialize javax.websocket layer
		JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {

			// This lambda will be called at the appropriate place in the
			// ServletContext initialization phase where you can initialize
			// and configure your websocket container.

			// give this instance to the PubSubConfigurator, so that it can pass it to the
			// PubSubEndpoint
			ServerEndpointConfig serverConfig = ServerEndpointConfig.Builder
					.create(PubSubEndpoint.class, "/")
					.configurator(new PubSubConfigurator(this)).build();

			// Add WebSocket endpoint to javax.websocket layer
			wsContainer.addEndpoint(serverConfig);
		});
	}

	public void publish(Object obj) {
		publish(DEFAULT_TOPIC, RestUtils.asJson(obj));
	}

	public void publish(String topic, Object obj) {
		publish(topic, RestUtils.asJson(obj));
	}

	public void publishAllTopics(Object obj, Set<String> topicsToSkip) {

		// iterating ConcurrentHashMap should be safe
		for (String topicName : topics.keySet()) {
			if (!topicsToSkip.contains(topicName)) {
				this.publish(topicName, obj);
			}
		}
	}

	private void publish(String topicName, String msg) {
		Topic topic = topics.get(topicName);
		if (topic != null) {
			topic.publish(msg);
			this.messagesSent += topic.getSubscribers().size();
			this.bytesSent += topic.getSubscribers().size() * msg.length();
		} else {
			this.messagesDiscarded++;
			logger.debug("no one listening on topic: " + topicName);
		}
		this.messagesReceived++;
		this.bytesReceived += msg.length();
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
			this.subsribeCount++;
		}
	}

	public void unsubscribe(String topicName, Basic basicRemote) {

		if (topicName == null) {
			topicName = DEFAULT_TOPIC;
		}

		synchronized (topics) {
			Topic topic = topics.get(topicName);
			if (topic != null) {
				topic.remove(basicRemote);
				if (topic.isEmpty()) {
					logger.debug("topic " + topicName + " is empty, remove it");
					topics.remove(topicName);
				}
			} else {
				logger.warn("cannot unssubscribe, topic not found: " + topic);
			}
		}
	}

	public MessageHandler.Whole<String> getMessageHandler() {
		return this.replyHandler;
	}

	public void startPingTimer() {
		pingTimer = new Timer();

		if (pingInterval != 0) {
			// prevent jetty from closing this connection if it is idle for 5 minutes
			// or the haproxy in OpenShift after one hour
			pingTimer.schedule(new TimerTask() {
				@Override
				public void run() {
					for (Topic topic : topics.values()) {
						topic.ping();
					}
				}
			}, pingInterval, pingInterval);
		}
	}

	public void stopPingTimer() {
		pingTimer.cancel();
	}

	public void stop() {
		try {
			stopPingTimer();
			this.server.stop();
			while (!this.server.isStopped()) {
				logger.info("waiting a pub-sub server to stop: " + name);

				Thread.sleep(1000);
			}
			logger.info("stopped a pub-sub server: " + name);
		} catch (Exception e) {
			logger.warn("failed to stop the pub-sub server", e);
		}
	}

	public void start() {
		try {
			logger.debug("start a pub-sub server: " + name);
			startPingTimer();
			this.server.start();
		} catch (Exception e) {
			logger.error("failed to start PubSubServer", e);
		}
	}

	public TopicConfig getTopicConfig() {
		return this.topicConfig;
	}

	public boolean isTopicAuthorized(AuthPrincipal principal, String topic) throws NotAuthorizedException {
		if (topicConfig != null) {
			return topicConfig.isAuthorized(principal, topic);
		} else {
			return true;
		}
	}

	@Override
	public Map<String, Object> getStatus() {
		HashMap<String, Object> status = new HashMap<>();

		synchronized (topics) {

			// group by tag

			// all tags
			List<String> tags = topicConfig.getMonitoringTags();

			for (String tag : tags) {

				// all topics with this tag
				Set<String> tagTopics = this.topics.keySet().stream()
						.filter(t -> tag.equals(topicConfig.getMonitoringTag(t)))
						.collect(Collectors.toSet());

				// count of topics with this tag
				status.put("wsTopicCount" + tag, tagTopics.size());

				// total count of subscribers in the topics with this tag
				status.put("wsSubscribersCurrent" + tag, tagTopics.stream()
						.mapToInt(t -> topics.get(t).getSubscribers().size()).sum());
			}
			status.put("wsMessagesDiscarded", this.messagesDiscarded);
			status.put("wsMessagesReceived", this.messagesReceived);
			status.put("wsMessagesSent", this.messagesSent);
			status.put("wsSubscribersTotal", this.subsribeCount);
			status.put("wsBytesSent", this.bytesSent);
			status.put("wsBytesReceived", this.bytesReceived);
		}

		return status;
	}

	/**
	 * @return an untyped thread-safe copy of topics for JSON serialization
	 */
	public HashMap<String, Object> getTopics() {
		synchronized (topics) {

			HashMap<String, Object> topicsCopy = new HashMap<>();

			for (String topicName : topics.keySet()) {
				ArrayList<Object> subscribersCopy = new ArrayList<>();

				ConcurrentHashMap<Basic, Subscriber> subscribers = topics.get(topicName).getSubscribers();
				for (Basic remote : subscribers.keySet()) {
					Subscriber subscriber = subscribers.get(remote);
					HashMap<String, Object> subscriberCopy = new HashMap<>();

					subscriberCopy.put("address", subscriber.getRemoteAddress());
					subscriberCopy.put("username", subscriber.getUsername());
					subscriberCopy.put("created", subscriber.getCreated());

					subscriberCopy.putAll(subscriber.getDetails());

					subscribersCopy.add(subscriberCopy);
				}

				topicsCopy.put(topicName, subscribersCopy);
			}

			return topicsCopy;
		}
	}

	public long getIdleTimeout() {
		return this.idleTimeout;
	}

	/**
	 * Configure {@link javax.websocket.Session#setMaxIdleTimeout(long)} for the
	 * websocket session
	 * 
	 * @param timeout
	 */
	public void setIdleTimeout(long timeout) {
		logger.info(name + " idle timeout: " + timeout + "ms");
		this.idleTimeout = timeout;
	}

	/**
	 * Send regular ping messages to all connected subscribers
	 * 
	 * This helps against this Jetty and other possible intermediate proxies to
	 * closing the idle connection.
	 * Settings this is only effective before the method start() is called.
	 * 
	 * @param pingInterval in milliseconds
	 */
	public void setPingInterval(long pingInterval) {
		logger.info(name + " ping interval: " + pingInterval + "ms");
		this.pingInterval = pingInterval;
	}
}
