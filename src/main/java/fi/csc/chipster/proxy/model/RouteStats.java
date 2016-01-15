package fi.csc.chipster.proxy.model;

import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

@XmlRootElement
public class RouteStats {

	private Route route;
	private long requestCount;
	private long openConnectionCount;
	
	@JsonUnwrapped
	public Route getRoute() {
		return route;
	}
	public void setRoute(Route route) {
		this.route = route;
	}
	public long getRequestCount() {
		return requestCount;
	}
	public void setRequestCount(long requestCount) {
		this.requestCount = requestCount;
	}
	public long getOpenConnectionCount() {
		return openConnectionCount;
	}
	public void setOpenConnectionCount(long openConnectionCount) {
		this.openConnectionCount = openConnectionCount;
	}
}
