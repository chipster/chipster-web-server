package fi.csc.chipster.servicelocator;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.servicelocator.resource.ServiceResource;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;

public class ServiceLocatorClient {

	public static final String CONF_KEY_USE_EXTERNAL_ADDRESSES = "use-external-addresses";

	private static final String CONF_KEY_URL_INT_OVERRIDE_PREFIX = "url-int-override-";
	private static final String CONF_KEY_URL_EXT_OVERRIDE_PREFIX = "url-ext-override-";

	private static final Logger logger = LogManager.getLogger();

	private String baseUri;
	private CredentialsProvider credentials;

	private boolean useExternalAddresses;

	private Config config;

	public ServiceLocatorClient(Config config) throws IOException {
		this.config = config;
		this.baseUri = config.getInternalServiceUrls().get(Role.SERVICE_LOCATOR);
		this.useExternalAddresses = config.getBoolean(CONF_KEY_USE_EXTERNAL_ADDRESSES);
		logger.info("get services from " + baseUri);
	}

	public ServiceLocatorClient(String baseUri) {
		this.baseUri = baseUri;
		// get config default
		this.useExternalAddresses = new Config().getBoolean(CONF_KEY_USE_EXTERNAL_ADDRESSES);
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

		return getInternalServices(credentials.getUsername(), credentials.getPassword());
	}

	public List<Service> getInternalServices(String username, String password) {

		WebTarget serviceTarget = AuthenticationClient.getClient(username, password, true)
				.target(baseUri).path(ServiceResource.PATH_SERVICES).path(ServiceResource.PATH_INTERNAL);

		String servicesJson = serviceTarget.request(MediaType.APPLICATION_JSON).get(String.class);

		@SuppressWarnings("unchecked")
		List<Service> services = RestUtils.parseJson(List.class, Service.class, servicesJson);

		// use external addresses in the services that don't run in the same private
		// network
		if (this.useExternalAddresses) {
			for (Service service : services) {
				if (service.getPublicUri() != null) {
					service.setUri(service.getPublicUri());
				}
			}
		}

		return services;
	}

	/**
	 * Public URIs are available without authentication
	 * 
	 * @param role
	 * @return
	 */
	public String getPublicUri(String role) {

		if (config != null) {
			// allow addresses from service locator to be overridden in config (file or env)
			String configUri = config.getString(CONF_KEY_URL_EXT_OVERRIDE_PREFIX + role);

			if (configUri != null && !configUri.isEmpty()) {
				logger.info("external address for " + role + " overridden in configuration: " + configUri);
				return configUri;
			}
		}

		return filterByRole(getPublicServices(), role).getPublicUri();
	}

	public String getInternalUri(String role) {

		if (config != null) {

			// allow addresses from service locator to be overridden in config (file or env)
			String configUri = config.getString(CONF_KEY_URL_INT_OVERRIDE_PREFIX + role);

			if (configUri != null && !configUri.isEmpty()) {
				logger.info("internal address for " + role + " overridden in configuration: " + configUri);
				return configUri;
			}
		}

		return filterByRole(getInternalServices(), role).getUri();
	}

	public Service getInternalService(String role) {

		return filterByRole(getInternalServices(), role);
	}

	public Set<Service> getPublicServices(String role) {

		return getPublicServices().stream()
				.filter(s -> s.getRole().startsWith(role))
				.collect(Collectors.toSet());
	}

	public Set<Service> getInternalServices(String role) {

		return getInternalServices().stream()
				.filter(s -> s.getRole().startsWith(role))
				.collect(Collectors.toSet());
	}

	public Set<String> getPublicUris(String role) {

		return getPublicServices(role).stream()
				.map(s -> s.getPublicUri())
				.collect(Collectors.toSet());
	}

	public Set<String> getInternalUris(String role) {

		return getInternalServices(role).stream()
				.map(s -> s.getPublicUri())
				.collect(Collectors.toSet());
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
