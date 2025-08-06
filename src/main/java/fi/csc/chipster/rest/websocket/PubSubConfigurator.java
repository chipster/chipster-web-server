package fi.csc.chipster.rest.websocket;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import jakarta.websocket.server.ServerEndpointConfig.Configurator;

/**
 * Configure the endpoint instance and user properties of connection
 * 
 * This class passes necessary information when the endpoint instance is created
 * and when a connection is created.
 * 
 * The endpoint instance is created by Jetty, but it must access somehow the
 * state of the PubSubServer. We'll override the method
 * getEndpointInstance() to pass the PubSubServer instance to the endpoint, when
 * ever Jetty decides to create the endpoint. See
 * https://stackoverflow.com/questions/17936440/accessing-httpsession-from-httpservletrequest-in-a-web-socket-serverendpoint
 * .
 * 
 * The second job of this class is to pass information from the HTTP upgrade
 * request to the WebSocket connection. This happens in
 * a method modifyHandshake(). There we can access to the HTTP headers and can
 * store those to the user properties of the WebSocket
 * connection.
 * 
 * @author klemela
 */
public class PubSubConfigurator extends Configurator {

	public static final String X_FORWARDED_FOR = "X-Forwarded-For";

	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	private PubSubServer server;

	public PubSubConfigurator(PubSubServer pubSubServer) {
		this.server = pubSubServer;
	}

	@Override
	public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {

		super.modifyHandshake(config, request, response);

		List<String> xForwaredForList = request.getHeaders().get(X_FORWARDED_FOR);
		String xForwaredFor = null;

		if (xForwaredForList != null && xForwaredForList.size() > 0) {
			xForwaredFor = xForwaredForList.get(0);
		}

		config.getUserProperties().put(X_FORWARDED_FOR, xForwaredFor);
	}

	@Override
	public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
		// let Jetty to create the PubSubServer
		T endpoint = super.getEndpointInstance(endpointClass);

		// and configure it
		((PubSubEndpoint) endpoint).setServer(server);
		return endpoint;
	}
}
