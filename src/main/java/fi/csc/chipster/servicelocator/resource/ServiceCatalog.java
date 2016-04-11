package fi.csc.chipster.servicelocator.resource;

import java.util.Collection;
import java.util.HashMap;

import fi.csc.chipster.rest.RestUtils;

public class ServiceCatalog {

	HashMap<String, Service> services = new HashMap<>();
	private boolean readOnly;

	public synchronized Collection<Service> getAll() {
		return services.values();
	}

	public synchronized String add(String role, Service service) {
		if (!readOnly) {
			String id = RestUtils.createId();
			service.setServiceId(id);
			services.put(id, service);
			return id;
		}
		// services should really just create the id by themselves
		return RestUtils.createId();
	}

	public synchronized Service remove(String id) {
		if (!readOnly) {
			return services.remove(id);
		}
		return null;
	}

	public void setReadOnly(boolean readOnly) {
		this.readOnly = readOnly; 
	}
}
