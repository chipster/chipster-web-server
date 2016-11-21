package fi.csc.chipster.servicelocator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.resource.Service;

public class ServiceLocatorClient {

	private String baseUri;

	public ServiceLocatorClient(Config config) throws IOException {
		this.baseUri = config.getString("service-locator");
	}

	public List<String> get(String role) {
		List<String> uriList = new ArrayList<>();
		
		List<Service> services = getServices(role);
		
		for (Service service : services) {
			if (role.equals(service.getRole())) {
				uriList.add(service.getUri());
			}
		}
		
		return uriList;
	}

	public List<Service> getServices(String role) {
		WebTarget serviceTarget = AuthenticationClient.getClient().target(baseUri).path("services");

		String servicesJson = serviceTarget.request(MediaType.APPLICATION_JSON).get(String.class);
		
		@SuppressWarnings("unchecked")
		List<Service> services = RestUtils.parseJson(List.class, Service.class, servicesJson);

		return services;
	}
}
