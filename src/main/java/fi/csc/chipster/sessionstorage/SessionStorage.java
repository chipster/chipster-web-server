package fi.csc.chipster.sessionstorage;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.CommonProperties;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.CORSResponseFilter;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.Server;
import fi.csc.chipster.rest.exception.NotFoundExceptionMapper;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessionstorage.model.Authorization;
import fi.csc.chipster.sessionstorage.model.Dataset;
import fi.csc.chipster.sessionstorage.model.File;
import fi.csc.chipster.sessionstorage.model.Input;
import fi.csc.chipster.sessionstorage.model.Job;
import fi.csc.chipster.sessionstorage.model.Parameter;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.chipster.sessionstorage.resource.Events;
import fi.csc.chipster.sessionstorage.resource.SessionResource;

/**
 * Main class.
 *
 */
public class SessionStorage implements Server {

	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(SessionStorage.class
			.getName());

	private static HibernateUtil hibernate;

	private String serviceId;

	private Events events;

	private ServiceLocatorClient serviceLocator;

	private AuthenticationClient authService;

	private Config config;

	public SessionStorage(Config config) {
		this.config = config;
	}

	/**
	 * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
	 * application.
	 * 
	 * @return Grizzly HTTP server.
	 */
	@Override
	public HttpServer startServer() {

		String username = "sessionStorage";
		String password = "sessionStoragePassword";

		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username,
				password);
		this.serviceId = serviceLocator.register(Role.SESSION_STORAGE,
				authService, config.getString("session-storage"));

		List<Class<?>> hibernateClasses = Arrays.asList(new Class<?>[] {
				Session.class, Dataset.class, Job.class, Parameter.class,
				Input.class, File.class, Authorization.class, });

		// init Hibernate
		hibernate = new HibernateUtil();
		hibernate.buildSessionFactory(hibernateClasses, "chipster-session-db");

		this.events = new Events(serviceId);

		final ResourceConfig rc = new ResourceConfig()
		/*
		 * Disable auto discovery so that we can decide what we want to register
		 * and what not. Don't register JacksonFeature, because it will register
		 * JacksonMappingExceptionMapper, which annoyingly swallows response's
		 * JsonMappingExceptions. Register directly the JacksonJaxbJsonProvider
		 * which is enough for the actual JSON conversion (see the code of
		 * JacksonFeature).
		 */
				.property(CommonProperties.FEATURE_AUTO_DISCOVERY_DISABLE, true)
				.register(JacksonJaxbJsonProvider.class)
				.packages(NotFoundExceptionMapper.class.getPackage().getName())
				.register(new SessionResource(hibernate, events))
				.register(new HibernateRequestFilter(hibernate))
				.register(new HibernateResponseFilter(hibernate))
				.register(RolesAllowedDynamicFeature.class)
				.register(CORSResponseFilter.class)
				// .register(new LoggingFilter())
				.register(new TokenRequestFilter(authService));

		// create and start a new instance of grizzly http server
		// exposing the Jersey application at BASE_URI
		return GrizzlyHttpServerFactory.createHttpServer(
				URI.create(getBaseUri()), rc);
	}

	/**
	 * Main method.
	 * 
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {

		final HttpServer server = new SessionStorage(new Config())
				.startServer();
		RestUtils.waitForShutdown("session storage", server);

		hibernate.getSessionFactory().close();
	}

	public static HibernateUtil getHibernate() {
		return hibernate;
	}

	@Override
	public void close() {
		events.close();
	}

	@Override
	public String getBaseUri() {
		return this.config.getString("session-storage-bind");
	}
}
