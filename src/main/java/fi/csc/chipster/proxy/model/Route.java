package fi.csc.chipster.proxy.model;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class Route {
	private String proxyPath;
	private String proxyTo;
	
	public Route() {}
	
	public Route(String proxyPath2, String proxyTo2) {
		this.proxyPath = proxyPath2;
		this.proxyTo = proxyTo2;
	}
	public String getProxyPath() {
		return proxyPath;
	}
	public void setProxyPath(String proxyPath) {
		this.proxyPath = proxyPath;
	}
	public String getProxyTo() {
		return proxyTo;
	}
	public void setProxyTo(String targetURI) {
		this.proxyTo = targetURI;
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((proxyPath == null) ? 0 : proxyPath.hashCode());
		result = prime * result + ((proxyTo == null) ? 0 : proxyTo.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Route other = (Route) obj;
		if (proxyPath == null) {
			if (other.proxyPath != null)
				return false;
		} else if (!proxyPath.equals(other.proxyPath))
			return false;
		if (proxyTo == null) {
			if (other.proxyTo != null)
				return false;
		} else if (!proxyTo.equals(other.proxyTo))
			return false;
		return true;
	}
}
