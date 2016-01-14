package fi.csc.chipster.proxy.model;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class Route {
	private String proxyPath;
	private String targetURI;
	private int connections;
	
	public String getProxyPath() {
		return proxyPath;
	}
	public void setProxyPath(String proxyPath) {
		this.proxyPath = proxyPath;
	}
	public String getTargetURI() {
		return targetURI;
	}
	public void setTargetURI(String targetURI) {
		this.targetURI = targetURI;
	}
	public int getConnections() {
		return connections;
	}
	public void setConnections(int connections) {
		this.connections = connections;
	}
}
