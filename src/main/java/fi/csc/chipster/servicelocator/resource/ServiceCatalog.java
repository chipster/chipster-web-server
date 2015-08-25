package fi.csc.chipster.servicelocator.resource;

import java.util.Collection;
import java.util.HashMap;

import fi.csc.chipster.rest.RestUtils;

public class ServiceCatalog {

	HashMap<String, Service> services = new HashMap<>();
	
	public synchronized Collection<Service> getAll() {
		return services.values();
	}

	public synchronized String add(String role, Service service) {
		String id = RestUtils.createId();
		service.setServiceId(id);
		services.put(id, service);
		return id;
	}

	public synchronized Service remove(String id) {
		return services.remove(id);
	}
}
