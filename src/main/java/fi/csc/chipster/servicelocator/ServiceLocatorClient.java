package fi.csc.chipster.servicelocator;

import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.servicelocator.resource.ServiceResource;

public class ServiceLocatorClient {

	private String baseUri;

	public ServiceLocatorClient(Config config) {
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

	public String register(String role, AuthenticationClient authService, String serviceBaseUri) {
		
		WebTarget target = authService.getAuthenticatedClient().target(this.baseUri).path(ServiceResource.SERVICES);
		Service service = new Service(role, serviceBaseUri);
		Response response = target.request(MediaType.APPLICATION_JSON_TYPE).post(Entity.entity(service, MediaType.APPLICATION_JSON_TYPE), Response.class);
		if (response.getStatus() != 201) {
			throw new InternalServerErrorException("incorrect status code when registering to service locator: " + response.getStatus() + " " +  response.getEntity());
		}
		return RestUtils.basename(response.getLocation().toString());
	}
}
