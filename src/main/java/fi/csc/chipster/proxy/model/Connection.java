package fi.csc.chipster.proxy.model;

import java.time.LocalDateTime;

import javax.ws.rs.core.UriBuilder;
import javax.xml.bind.annotation.XmlRootElement;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@XmlRootElement // json
public class Connection {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();
	
	private Route route;
	private String sourceAddress;
	private String requestURI;
	private LocalDateTime openTime;
	private LocalDateTime closeTime;
	private String method;
	
	public String getSourceAddress() {
		return sourceAddress;
	}
	public void setSourceAddress(String sourceAddress) {
		this.sourceAddress = sourceAddress;
	}
	public LocalDateTime getOpenTime() {
		return openTime;
	}
	public void setOpenTime(LocalDateTime openTime) {
		this.openTime = openTime;
	}
	public LocalDateTime getCloseTime() {
		return closeTime;
	}
	public void setCloseTime(LocalDateTime closeTime) {
		this.closeTime = closeTime;
	}
	public String getRequestURI() {
		return requestURI;
	}
	public void setRequestURI(String requestPath) {
		// remove query string to hide tokens
		UriBuilder builder = UriBuilder.fromUri(requestPath);
		builder.replaceQuery(null);
		this.requestURI = builder.toString();
	}
	public Route getRoute() {
		return route;
	}
	public void setRoute(Route route) {
		this.route = route;
	}
	public String getMethod() {
		return method;
	}
	public void setMethod(String method) {
		this.method = method;
	}
}
