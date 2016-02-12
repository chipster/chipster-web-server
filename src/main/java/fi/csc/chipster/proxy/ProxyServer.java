package fi.csc.chipster.proxy;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.Servlet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;

import fi.csc.chipster.proxy.ConnectionManager.ConnectionListener;
import fi.csc.chipster.proxy.model.Connection;
import fi.csc.chipster.proxy.model.Route;
import fi.csc.chipster.proxy.model.RouteStats;

/**
 * Switchover proxy
 * 
 * Proxy service for proxying HTTP and websocket traffic. The proxy routes can be
 * added and removed on the fly. When an existing proxy route is changed, the remaining
 * connections stay connected to the previous target, but all new connections will be 
 * routed to the new target.
 *
 * Implemented on top of Jetty's ProxyServlet and Jetty's WebSocket API. The switchover
 * functionality is based on servlet mappings. A new servlet is created for each route and 
 * requests to the route's path are mapped to that servlet. On switchover, a new servlet is
 * created, and the mapping is update to relay new connections to the new servlet. The old 
 * servlet continues to relay existing connections and is removed when all of them are closed.
 * 
 * Limitations:
 * - HTTP errors on websocket upgrade request won't cause the original upgrade request to 
 * fail with a HTTP error, but the error is converted to a websocket error 
 * - the same proxy path can't relay both HTTP and websocket traffic
 * 
 * @author klemela
 *
 */
public class ProxyServer {
		
	private static final Logger logger = LogManager.getLogger();
	
	public static final String PREFIX = "prefix";
	public static final String PROXY_TO = "proxyTo";

	private Server jetty;
	private ServletContextHandler context;

	private ConcurrentHashMap<ServletHolder, String> servletsToRemove = new ConcurrentHashMap<>();

	private ConnectionManager connectionManager;
		
    public static void main(String[] args) throws Exception {

    	// bind to localhost port 8000
    	ProxyServer proxy = new ProxyServer(new URI("http://127.0.0.1:8000"));
    	
    	// proxy requests from localhost:8000/test to chipster.csc.fi
    	proxy.addRoute("test", "http://chipster.csc.fi");    	
    	
    	// proxying websockets is as easy
    	//proxy.addRoute("websocket-path-on-proxy", "http://websocket-server-host");
    	
    	proxy.startServer();
    	logger.info("proxy up and running");
    }
    
    public ProxyServer(URI baseUri) {
    	
    	this.connectionManager = new ConnectionManager();
    	this.connectionManager.setListener(new ConnectionListener() {
			@Override
			public void connectionRemoved(Connection connection) {
				removeUnusedServlets();
			}
    	});
    	
        this.jetty = new Server();
        
        ServerConnector connector = new ServerConnector(jetty);
        connector.setPort(baseUri.getPort());
        connector.setHost(baseUri.getHost());
        jetty.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        
        boolean enableJMX = true; 
        if (enableJMX) {
        	// enable JMX to investigate Jetty internals with jconsole
        	// on OS X set JVM argument -Djava.rmi.server.hostname=localhost to be able to connect
        	MBeanContainer mbContainer=new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        	jetty.addBean(mbContainer);
        	StatisticsHandler statisticsHandler = new StatisticsHandler();
        	
        	// settings as root handler will give only the overall statistics
        	statisticsHandler.setHandler(handlers);
        	jetty.setHandler(statisticsHandler);
        	
        } else {
        	jetty.setHandler(handlers);        	
        }
        
        this.context = new ServletContextHandler(handlers, "/", ServletContextHandler.SESSIONS);
    }
    

	public void startServer() {
        try {        	        
			jetty.start();
		} catch (Exception e) {
			logger.error("failed to start proxy", e);
		}
	}
	
	public void close() {
		try {
			this.jetty.stop();
		} catch (Exception e) {
			logger.warn("failed to stop the proxy", e);
		}
	}
    
	/**
	 * Add a new route. The remaining connections will stay connected to the old target.
	 * 
	 * @param proxyPath
	 * @param targetUri
	 * @throws URISyntaxException
	 */
	public void addRoute(String proxyPath, String targetUri) throws URISyntaxException {
	
		logger.info("add route " + proxyPath + " -> " + targetUri);
		ServletHolder routeServlet;
		String scheme = new URI(targetUri).getScheme().toLowerCase();
		if ("ws".equals(scheme) || "wss".equals(scheme)) {		
			Servlet servlet = new WebSocketProxyServlet(connectionManager);
			routeServlet = new ServletHolder(servlet);
		} else {		
			Servlet servlet = new HttpProxyServlet(connectionManager);
			routeServlet = new ServletHolder(servlet);
		}
        routeServlet.setInitParameter(PROXY_TO, targetUri);
        routeServlet.setInitParameter(PREFIX, "/" + proxyPath);
        
        context.getServletHandler().addServlet(routeServlet);
        updateMapping(proxyPath, routeServlet);
	}

	public List<Route> getRoutes() {
		
		ArrayList<Route> routes = new ArrayList<>();
		
		List<ServletMapping> mappings = Arrays.asList(context.getServletHandler().getServletMappings());
		for (ServletMapping mapping : mappings) {
			String pathSpec = mapping.getPathSpecs()[0];
			if (!isProxyPathSpec(pathSpec)) {
				//TODO this isn't needed, if the DefaultServlet is removed
				continue;
			}
			Route route = new Route();
			route.setProxyPath(getProxyPath(pathSpec));
			route.setProxyTo(getServlet(getProxyPath(pathSpec), mappings).getInitParameter(PROXY_TO));
			routes.add(route);
		}
		
		return routes;
	}
	
	public List<RouteStats> getRouteStats() {
		ArrayList<RouteStats> stats = new ArrayList<>();
		for (Route route : getRoutes()) {
			stats.add(connectionManager.getRouteStats(route));
		}
		return stats;
	}
	
	public void removeRoute(String proxyPath) {
		logger.info("remove route " + proxyPath);
		updateMapping(proxyPath, null);
	}
	
	public List<Connection> getConnections() {
		
		return connectionManager.getConnections();
	}
	
	private String getPathSpec(String proxyPath) {
		return "/" + proxyPath + "/*";
	}
	
	private String getProxyPath(String pathSpec) {
		if (isProxyPathSpec(pathSpec)) {
			return pathSpec.substring("/".length(), pathSpec.length() - "/*".length());
		} else {
			throw new IllegalArgumentException("illegal pathSpec");
		}
	}
	
	private boolean isProxyPathSpec(String pathSpec) {
		return pathSpec.startsWith("/") && pathSpec.endsWith("/*");
	}
	
	private ServletMapping getServletMapping(String proxyPath, List<ServletMapping> mappings) {
		// find the mapping for this path
		for (ServletMapping mapping : mappings) {
			String pathSpec = mapping.getPathSpecs()[0];
			if (pathSpec.equals(getPathSpec(proxyPath))) {
				return mapping;
			}
		}
		return null;
	}
	
	private ServletHolder getServlet(String proxyPath, List<ServletMapping> mappings) {
		ServletMapping mapping = getServletMapping(proxyPath, mappings);
		for (ServletHolder holder : context.getServletHandler().getServlets()) {
			if (holder.getName().equals(mapping.getServletName())) {
				return holder;
			}
		}
		return null;
	}
	
	
	/**
	 * Replace old mapping with a new one in one go
	 * 
	 * If there is an old mapping, it will be removed and its servlet will 
	 * be removed when it doesn't have anymore active connections.
	 * 
	 *  and removes the mapping, 
	 * if the newHolder parameter is null.
	 * 
	 * @param proxyPath
	 * @param newHolder
	 */
	public void updateMapping(String proxyPath, ServletHolder newHolder) {
		
		// see ServletHandler.addServletWithMapping()
		
		// get mappings (or create if this is the first mapping)
		ServletMapping[] mappingsArray = context.getServletHandler().getServletMappings();
		if (mappingsArray == null) {
			mappingsArray = new ServletMapping[0];
		}
		List<ServletMapping> mappings = new ArrayList<>(Arrays.asList(mappingsArray));
		
		ServletMapping oldMapping = getServletMapping(proxyPath, mappings);
		if (oldMapping != null) {
			ServletHolder oldServlet = getServlet(proxyPath, mappings);
			logger.info("old route " + proxyPath + " -> " + oldServlet.getInitParameter(PROXY_TO) + " will be removed later");
			// remove the servlet after all connections have closed
			servletsToRemove.put(oldServlet, proxyPath);
			mappings.remove(oldMapping);
		}
		
		if (newHolder != null) {
			ServletMapping mapping = new ServletMapping();
			mapping.setServletName(newHolder.getName());
			mapping.setPathSpec(getPathSpec(proxyPath));
			mappings.add(mapping);
		}
		
		// update the server
		context.getServletHandler().setServletMappings(mappings.toArray(new ServletMapping[0]));
		
		removeUnusedServlets();
	}
	
	/**
	 * This will interrupt all remaining connections served by this servlet. Remember to remove the
	 * mapping also.
	 * 
	 * @param holder
	 */
	public void removeServlet(ServletHolder holder) {
		List<ServletHolder> holders = new ArrayList<>(Arrays.asList(context.getServletHandler().getServlets()));
		holders.remove(holder);
		context.getServletHandler().setServlets(holders.toArray(new ServletHolder[0]));
	}

	private void removeUnusedServlets() {
		// servlets are removed only when it doesn't have connections anymore
		Iterator<ServletHolder> holdersIter = servletsToRemove.keySet().iterator();

		while (holdersIter.hasNext()) {
			ServletHolder holder = holdersIter.next();

			String holderProxyPath = servletsToRemove.get(holder);
			String holderTargetURI = holder.getInitParameter(PROXY_TO);

			if (!connectionManager.hasOpenConnections(new Route(holderProxyPath, holderTargetURI))) {
				logger.info("remove unused route " + holderProxyPath + " -> " + holderTargetURI);
				removeServlet(holder);
				holdersIter.remove();
			}
		}
	}
}