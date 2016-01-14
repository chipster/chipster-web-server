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
	
	private String sourceAddress;
	private String proxyPath;
	private String requestURI;
	private String proxyTo;
	private LocalDateTime openTime;
	private LocalDateTime closeTime;
	
	public String getSourceAddress() {
		return sourceAddress;
	}
	public void setSourceAddress(String sourceAddress) {
		this.sourceAddress = sourceAddress;
	}
	public String getProxyTo() {
		return proxyTo;
	}
	public void setProxyTo(String targetAddress) {
		this.proxyTo = targetAddress;
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
	public String getProxyPath() {
		return proxyPath;
	}
	public void setProxyPath(String proxyPath) {
		this.proxyPath = proxyPath;
	}
}
