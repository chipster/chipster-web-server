package fi.csc.chipster.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import fi.csc.chipster.proxy.model.Connection;
import fi.csc.chipster.proxy.model.Route;
import fi.csc.chipster.proxy.model.RouteStats;

/**
 * Track open connections
 * 
 * @author klemela
 *
 */
public class ConnectionManager {
	
	private ConcurrentHashMap<Route, RouteConnections> routes = new ConcurrentHashMap<>();
	
	public static class RouteConnections {
		private long requestCount = 0;
		private ConcurrentLinkedQueue<Connection> connections = new ConcurrentLinkedQueue<>();
		private Route route;
		
		public RouteConnections(Route route) {
			this.route = route;
		}
		public void addConnection(Connection connection) {
			connections.add(connection);
			requestCount++;
		}
		public void removeConnection(Connection connection) {
			connections.remove(connection);
		}
		public RouteStats getRouteStats() {
			RouteStats stats = new RouteStats();
			stats.setRoute(route);
			stats.setRequestCount(requestCount);
			stats.setOpenConnectionCount(connections.size());
			return stats;
		}	
	}
	
	public interface ConnectionListener {
		public void connectionRemoved(Connection connection);
	}
	
	private ConnectionListener listener;
	
	public void addConnection(Connection connection) {
		get(connection.getRoute()).addConnection(connection);
	}
	
	public void removeConnection(Connection connection) {
		get(connection.getRoute()).removeConnection(connection);
		if (listener != null) {
			listener.connectionRemoved(connection);
		}
	}
	
	public void setListener(ConnectionListener listener) {
		this.listener = listener;
	}
	
	public List<Connection> getConnections() {
		ArrayList<Connection> all = new ArrayList<>();
		for (Route route : routes.keySet()) {
			all.addAll(routes.get(route).connections);
		}
		return all;
	}
	
	public List<Connection> getConnections(Route route) {
		return new ArrayList<>(get(route).connections);
	}
	
	private RouteConnections get(Route route) {
		if (!routes.containsKey(route)) {
			routes.put(route, new RouteConnections(route));
		}
		return routes.get(route);
	}
	
	public boolean hasOpenConnections(Route route) {
		return !getConnections(route).isEmpty();
	}

	public RouteStats getRouteStats(Route route) {
		return get(route).getRouteStats();
	}
}