package fi.csc.chipster.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import fi.csc.chipster.proxy.model.Connection;

/**
 * Track open connections
 * 
 * @author klemela
 *
 */
public class ConnectionManager {
	
	public interface ConnectionListener {
		public void connectionRemoved(Connection connection);
	}
	
	private ConcurrentLinkedQueue<Connection> connections = new ConcurrentLinkedQueue<>();
	private ConnectionListener listener;
	
	public void addConnection(Connection connection) {
		connections.add(connection);
	}
	public void removeConnection(Connection connection) {
		connections.remove(connection);
		if (listener != null) {
			listener.connectionRemoved(connection);
		}
	}
	
	public void setListener(ConnectionListener listener) {
		this.listener = listener;
	}
	
	public List<Connection> getConnections() {
		return new ArrayList<>(connections);
	}
	
	public List<Connection> getConnections(String proxyPath, String targetURI) {
		ArrayList<Connection> list = new ArrayList<>();
		for (Connection connection : connections) {
			if (proxyPath.equals(connection.getProxyPath()) && targetURI.equals(connection.getProxyTo())) {
				list.add(connection);
			}
		}
		return list;
	}
	
	public boolean hasOpenConnections(String proxyPath, String targetURI) {
		// assumes that each servlet has a unique pair of proxyPath and targetURI
		return !getConnections(proxyPath, targetURI).isEmpty();
	}
}