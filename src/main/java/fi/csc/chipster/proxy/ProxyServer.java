package fi.csc.chipster.proxy;
import java.net.URI;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;

import fi.csc.chipster.rest.Config;

public class ProxyServer {
	
	private final Logger logger = LogManager.getLogger();

	private Server server;

	private Config config;
	
	public static final String PATH_PARAM = "pathParam";
	public static final String PREFIX = "prefix";
	public static final String PROXY_TO = "proxyTo";

    public static void main(String[] args) throws Exception {

    	new ProxyServer(new Config());
    }
    
    public ProxyServer(Config config) {
    	this.config =  config;       
    }
    

	public void startServer() {
        try {
        	URI baseUri = URI.create(config.getString("proxy-bind"));
        	
            this.server = new Server();
            ServerConnector connector = new ServerConnector(server);
            connector.setPort(baseUri.getPort());
            connector.setHost(baseUri.getHost());
            server.addConnector(connector);

            HandlerCollection handlers = new HandlerCollection();
            server.setHandler(handlers);

            ServletContextHandler context = new ServletContextHandler(handlers, "/", ServletContextHandler.SESSIONS);
            
            addHttpProxyRule(context, 		"sessiondb", 		config.getString("session-db"));
            addWebSocketProxyRule(context, 	"sessiondbevents", 	config.getString("session-db-events"), 2);
            addHttpProxyRule(context, 		"auth", 			config.getString("authentication-service"));
            addHttpProxyRule(context, 		"discovery", 		config.getString("service-locator"));
            
			server.start();
		} catch (Exception e) {
			logger.error("failed to start proxy", e);
		}
	}
    
    private static void addWebSocketProxyRule(ServletContextHandler context, String proxyPath, String targetUri, int pathParams) throws ServletException, DeploymentException {
        // Initialize javax.websocket layer
        ServerContainer wscontainer = WebSocketServerContainerInitializer.configureContext(context);
        
        // we have to specify the amount of pathParams, because a wildcard path isn't supported
        String path = "/" + proxyPath;
        for (int i = 0; i < pathParams; i++) {
        	path += "/{" + PATH_PARAM + i + "}"; 
        }
        
        targetUri = targetUri.replaceAll("/$", "");
        ServerEndpointConfig serverConfig = ServerEndpointConfig.Builder.create(WebSocketSourceEndpoint.class, path).build();
        serverConfig.getUserProperties().put(PROXY_TO, targetUri);
        serverConfig.getUserProperties().put(PREFIX, "/" + proxyPath);
        // Add WebSocket endpoint to javax.websocket layer
        wscontainer.addEndpoint(serverConfig);
	}

    private static void addHttpProxyRule(ServletContextHandler context, String proxyPath, String targetUri) {
    	ServletHolder proxyServlet = new ServletHolder(LoggingProxyServlet.class);
        proxyServlet.setInitParameter(PROXY_TO, targetUri);
        proxyServlet.setInitParameter(PREFIX, "/" + proxyPath);
        context.addServlet(proxyServlet, "/" + proxyPath + "/*");
	}

	public static class LoggingProxyServlet extends ProxyServlet.Transparent {
		
		private final Logger logger = LogManager.getLogger();
    	
        @Override
        protected String rewriteTarget(HttpServletRequest request)
        {
        	
    		StringBuffer original = request.getRequestURL();    		
            String rewritten =  super.rewriteTarget(request);
            
            logger.info("proxy " + original  + " \t -> " + rewritten);
            
            return rewritten;
        }       
    }
	
	
	public static CloseReason toCloseReason(Throwable e) {
		String msg = "proxy error: " + e.getClass().getSimpleName() + " " + e.getMessage();
		if (e.getCause() != null) {
			msg += " Caused by: " + e.getCause().getClass().getSimpleName() + " " + e.getCause().getMessage();
		}
		return new CloseReason(CloseCodes.UNEXPECTED_CONDITION, msg);
	}

	public void close() {
		try {
			this.server.stop();
		} catch (Exception e) {
			logger.warn("failed to stop the proxy", e);
		}
	}
}