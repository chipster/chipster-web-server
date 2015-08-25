package fi.csc.chipster.servicelocator.resource;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class Service {

	private String serviceId;
	private String role;
	private String uri;
	

	public Service(String role, String uri) {
		this.role = role;
		this.uri = uri;
	}
	
	public Service() {
	}
	
	public String getServiceId() {
		return serviceId;
	}
	public void setServiceId(String serviceId) {
		this.serviceId = serviceId;
	}

	public String getUri() {
		return uri;
	}

	public void setUri(String uri) {
		this.uri = uri;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

}
