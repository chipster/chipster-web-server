package fi.csc.chipster.servicelocator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.servicelocator.resource.Service;

/**
 * ServiceLocatorClient for local usage
 * 
 * When service-locator wants to know about services in the request filter, it
 * can't use
 * the ServiceLocatorClient, because that would make new request, which would
 * again call the filter,
 * creating an infinite loop. This version shares the same interface, but is
 * initialized with static data.
 * 
 * @author klemela
 *
 */
public class LocalServiceLocatorClient extends ServiceLocatorClient {

	private ArrayList<Service> publicServices;
	private ArrayList<Service> allServices;

	public LocalServiceLocatorClient(ArrayList<Service> publicServices, ArrayList<Service> allServices, Config config)
			throws IOException {
		super(config);

		this.publicServices = publicServices;
		this.allServices = allServices;
	}

	public List<Service> getPublicServices() {

		return publicServices;
	}

	public List<Service> getInternalServices() {

		return allServices;
	}

	public String getPublicUri(String role) {

		return super.getPublicUri(role);
	}

	public Service getInternalService(String role) {

		return super.getInternalService(role);
	}
}
