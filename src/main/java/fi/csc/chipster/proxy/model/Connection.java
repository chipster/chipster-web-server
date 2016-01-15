package fi.csc.chipster.proxy.model;

import java.time.LocalDateTime;

import javax.xml.bind.annotation.XmlRootElement;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sun.org.apache.xml.internal.utils.URI;
import com.sun.org.apache.xml.internal.utils.URI.MalformedURIException;

@XmlRootElement // json
public class Connection {
	
	private static final Logger logger = LogManager.getLogger();
	
	private Route route;
	private String sourceAddress;
	private String requestURI;
	private LocalDateTime openTime;
	private LocalDateTime closeTime;
	
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
		try {
			// remove query string to hide tokens
			URI uri = new URI(requestPath);
			uri.setQueryString(null);
			this.requestURI = uri.toString();
		} catch (MalformedURIException e) {
			logger.error("invalid request URI", e);
		}
	}
	public Route getRoute() {
		return route;
	}
	public void setRoute(Route route) {
		this.route = route;
	}
}
