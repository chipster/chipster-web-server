package fi.csc.chipster.servicelocator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.resource.Service;

public class ServiceLocatorClient {
	
	private static final Logger logger = LogManager.getLogger();

	private String baseUri;

	public ServiceLocatorClient(Config config) throws IOException {
		this.baseUri = config.getInternalServiceUrls().get(Role.SERVICE_LOCATOR);
		logger.info("get services from " + baseUri);
	}

	public List<String> get(String role) {
		List<String> uriList = new ArrayList<>();
		
		List<Service> services = getServices();
		
		for (Service service : services) {
			if (role.equals(service.getRole())) {
				uriList.add(service.getUri());
			}
		}
		
		return uriList;
	}
	
	public String getM2mUri(String role) {

		List<Service> services = getServices();
		
		for (Service service : services) {
			if (role.equals(service.getRole())) {
				return service.getM2mUri();
			}
		}
		
		return null;
	}

	public List<Service> getServices() {
		WebTarget serviceTarget = AuthenticationClient.getClient().target(baseUri).path("services");
		
		String servicesJson = serviceTarget.request(MediaType.APPLICATION_JSON).get(String.class);
		
		@SuppressWarnings("unchecked")
		List<Service> services = RestUtils.parseJson(List.class, Service.class, servicesJson);
	

		return services;
	}

	public Service getService(String role) {
		List<Service> services = getServices().stream()
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
}
