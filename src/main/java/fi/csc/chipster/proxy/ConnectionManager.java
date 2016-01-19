package fi.csc.chipster.proxy;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

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
		private AtomicLong requestCount = new AtomicLong();
		private ConcurrentLinkedQueue<Connection> connections = new ConcurrentLinkedQueue<>();
		private Route route;
		
		public RouteConnections(Route route) {
			this.route = route;
		}
		public void addConnection(Connection connection) {
	        connection.setOpenTime(LocalDateTime.now());
			connections.add(connection);
			requestCount.getAndIncrement();
		}
		public void removeConnection(Connection connection) {
			connection.setCloseTime(LocalDateTime.now());
			connections.removeAll(getOldConnections());
//			System.out.println(
//					ChronoUnit.MILLIS.between(connection.getOpenTime(), connection.getCloseTime()) + "\t" + 
//					connection.getMethod() + "\t" + 
//					connection.getRequestURI()); 
		}
		private ArrayList<Connection> getOpenConnections() {
			ArrayList<Connection> filtered = new ArrayList<>();
			for (Connection con : connections) {
				if (con.getCloseTime() == null) {
					filtered.add(con);
				}
			}
			return filtered;
		}
		private ArrayList<Connection> getLatestConnections() {
			ArrayList<Connection> filtered = new ArrayList<>();
			for (Connection con : connections) {
				if (con.getCloseTime() != null && con.getCloseTime().isAfter(LocalDateTime.now().minus(1, ChronoUnit.SECONDS))) {
					filtered.add(con);
				}
			}
			return filtered;
		}
		private ArrayList<Connection> getOldConnections() {
			ArrayList<Connection> filtered = new ArrayList<>();
			for (Connection con : connections) {
				if (con.getCloseTime() != null && con.getCloseTime().isBefore(LocalDateTime.now().minus(1, ChronoUnit.SECONDS))) {
					filtered.add(con);
				}
			}
			return filtered;
		}
		public RouteStats getRouteStats() {
			RouteStats stats = new RouteStats();
			stats.setRoute(route);
			stats.setRequestCount(requestCount.get());
			stats.setOpenConnectionCount(getOpenConnections().size());
			stats.setRequestsPerSecond(getLatestConnections().size());
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
			all.addAll(routes.get(route).getOpenConnections());
		}
		return all;
	}
	
	public List<Connection> getConnections(Route route) {
		return new ArrayList<>(get(route).getOpenConnections());
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