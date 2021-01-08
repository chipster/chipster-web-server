package fi.csc.chipster.rest.websocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import jakarta.websocket.server.ServerEndpointConfig.Configurator;

/**
 * Pass class instances to the endpoint class like shown in https://stackoverflow.com/questions/17936440/accessing-httpsession-from-httpservletrequest-in-a-web-socket-serverendpoint
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
		
		if (xForwaredForList != null) {
			xForwaredFor = xForwaredForList.get(0);
		}
		
		config.getUserProperties().put(X_FORWARDED_FOR, xForwaredFor);		
    }

	@Override
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
		T endpoint = super.getEndpointInstance(endpointClass);
		((PubSubEndpoint)endpoint).setServer(server);
		return endpoint;
//		PubSubEndpoint endpoint = new PubSubEndpoint(server);
//		return (T) endpoint;		
    }
}
