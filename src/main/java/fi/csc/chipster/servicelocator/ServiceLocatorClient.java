package fi.csc.chipster.servicelocator;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.servicelocator.resource.ServiceResource;

public class ServiceLocatorClient {
	
	private static final Logger logger = LogManager.getLogger();

	private String baseUri;
	private CredentialsProvider credentials;
	
	public ServiceLocatorClient(Config config) throws IOException {
		this.baseUri = config.getInternalServiceUrls().get(Role.SERVICE_LOCATOR);
		logger.info("get services from " + baseUri);
	}

	/**
	 * Public resource returns full Service objects but most fields are null
	 * 
	 * @return
	 */
	public List<Service> getPublicServices() {
		
		WebTarget serviceTarget = AuthenticationClient.getClient().target(baseUri)
					.path(ServiceResource.PATH_SERVICES);	

		String servicesJson = serviceTarget.request(MediaType.APPLICATION_JSON).get(String.class);
		
		@SuppressWarnings("unchecked")
		List<Service> services = RestUtils.parseJson(List.class, Service.class, servicesJson);

		return services;
	}
	
	public List<Service> getInternalServices() {
		if (credentials == null) {
			throw new IllegalArgumentException("only public URIs are available without the authentication");
		}
		
		WebTarget serviceTarget = AuthenticationClient.getClient(credentials.getUsername(), credentials.getPassword(), true)
				.target(baseUri).path(ServiceResource.PATH_SERVICES).path(ServiceResource.PATH_INTERNAL);

		String servicesJson = serviceTarget.request(MediaType.APPLICATION_JSON).get(String.class);
		
		@SuppressWarnings("unchecked")
		List<Service> services = RestUtils.parseJson(List.class, Service.class, servicesJson);

		return services;
	}
	
	/**
	 * Public URIs are available without authentication 
	 * 
	 * @param role
	 * @return
	 */
	public String getPublicUri(String role) {

		return filterByRole(getPublicServices(), role).getPublicUri();
	}	
	
	public Service getInternalService(String role) {

		return filterByRole(getInternalServices(), role);
	}

	private Service filterByRole(List<Service> services, String role) {
		services = services.stream()
			.filter(s -> role.equals(s.getRole()))
			.collect(Collectors.toList());
		
		if (services.isEmpty()) {
			throw new IllegalStateException("service " + role + " not found");
		}
		
		if (services.size() > 1) {
			logger.warn(services.size() + " " + role + " services, using the first one");
		}
		
		return services.get(0);
	}

	public void setCredentials(CredentialsProvider credentials) {
		this.credentials = credentials;
	}
	
	public String getBaseUri() {
		return this.baseUri;
	}
}
