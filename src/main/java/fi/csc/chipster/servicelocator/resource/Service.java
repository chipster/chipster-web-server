package fi.csc.chipster.servicelocator.resource;

import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class Service {

	private String serviceId;
	private String role;

	// inconsistent naming for historical reasons
	// internal address of the client API
	private String uri;
	// external address of the client API
	private String publicUri;
	// external address of the admin/m2m API
	private String adminUri;
	// internal address of the admin/m2m API
	private String internalAdminUri;

	public Service(String role, String uri, String publicUri, String adminUri, String internalAdminUri) {
		this.role = role;
		this.uri = uri;
		this.publicUri = publicUri;
		this.adminUri = adminUri;
		this.internalAdminUri = internalAdminUri;
	}

	public Service() {
	}

	public String getServiceId() {
		return serviceId;
	}

	public void setServiceId(String serviceId) {
		this.serviceId = serviceId;
	}

	/**
	 * Get the internal URI
	 * 
	 * @return
	 */
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

	public String getPublicUri() {
		return publicUri;
	}

	public void setPublicUri(String publicUri) {
		this.publicUri = publicUri;
	}

	public String getAdminUri() {
		return adminUri;
	}

	public void setAdminUri(String adminUri) {
		this.adminUri = adminUri;
	}

	public String getInternalAdminUri() {
		return internalAdminUri;
	}

	public void setInternalAdminUri(String privateAdminUri) {
		this.internalAdminUri = privateAdminUri;
	}
}
