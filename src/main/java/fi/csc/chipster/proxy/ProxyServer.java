package fi.csc.chipster.proxy;
import java.net.URI;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.DeploymentException;
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

public class ProxyServer {
	
	private static final Logger logger = LogManager.getLogger();

	private Server server;
	private ServletContextHandler context;
	
	public static final String PATH_PARAM = "pathParam";
	public static final String PREFIX = "prefix";
	public static final String PROXY_TO = "proxyTo";

    public static void main(String[] args) throws Exception {

    	// bind to localhost port 8000
    	ProxyServer proxy = new ProxyServer(new URI("http://127.0.0.1:8000"));
    	
    	// proxy requests from localhost:8000/test to chipster.csc.fi
    	proxy.addHttpProxyRule("test", "http://chipster.csc.fi");    	
    	
    	// proxying websockets is almost as easy
    	//proxy.addWebSocketProxyRule("websocket-path-on-proxy", "http://websocket-server-host", 2);
    	
    	proxy.startServer();
    	logger.info("proxy up and running");
    }
    
    public ProxyServer(URI baseUri) {
    	
        this.server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(baseUri.getPort());
        connector.setHost(baseUri.getHost());
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        this.context = new ServletContextHandler(handlers, "/", ServletContextHandler.SESSIONS);
    }
    

	public void startServer() {
        try {        	        
			server.start();
		} catch (Exception e) {
			logger.error("failed to start proxy", e);
		}
	}
    
	public void addWebSocketProxyRule(String proxyPath, String targetUri, int pathParams) throws ServletException, DeploymentException {
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

    public void addHttpProxyRule(String proxyPath, String targetUri) {
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
            String rewritten =  super.rewriteTarget(request);
            
            StringBuffer original = request.getRequestURL();    		
            logger.debug("proxy " + original  + " \t -> " + rewritten);
            
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