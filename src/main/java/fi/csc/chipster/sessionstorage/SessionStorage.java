package fi.csc.chipster.sessionstorage;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.Server;
import fi.csc.chipster.rest.hibernate.Hibernate;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.provider.NotFoundExceptionMapper;
import fi.csc.chipster.rest.token.AuthenticatedTarget;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocator;
import fi.csc.chipster.servicelocator.resource.Service;
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
	
	private static Logger logger = Logger.getLogger(SessionStorage.class.getName());
	
    // Base URI the Grizzly HTTP server will listen on
    public static final String BASE_URI = "http://localhost:8080/sessionstorage/";

	private static Hibernate hibernate;

	private String serviceId;

	private Events events;

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     */
    @Override
    public HttpServer startServer() {
    	
    	WebTarget serviceLocator = new AuthenticatedTarget("sessionStorage", "sessionStoragePassword").target(ServiceLocator.BASE_URI).path("services");
    	Service service = new Service(Role.SESSION_STORAGE, BASE_URI);
    	Response response = serviceLocator.request(MediaType.APPLICATION_JSON_TYPE).post(Entity.entity(service, MediaType.APPLICATION_JSON_TYPE),Response.class);
    	if (response.getStatus() != 201) {
    		throw new InternalServerErrorException("incorrect status code when registering to service locator: " + response.getStatus() + " " +  response.getEntity());
    	}
    	this.serviceId = RestUtils.basename(response.getLocation().toString());
    	
    	// show jersey logs in console
    	Logger l = Logger.getLogger(HttpHandler.class.getName());
    	l.setLevel(Level.FINE);
    	l.setUseParentHandlers(false);
    	ConsoleHandler ch = new ConsoleHandler();
    	ch.setLevel(Level.ALL);
    	l.addHandler(ch);
    	
    	List<Class<?>> hibernateClasses = Arrays.asList(new Class<?>[] {
    			Session.class,
    			Dataset.class,
    			Job.class,
    			Parameter.class,
    			Input.class,
    			File.class,
    			Authorization.class,
    	});
    	
    	// init Hibernate
    	hibernate = new Hibernate();
    	hibernate.buildSessionFactory(hibernateClasses, "chipster-session-db");
    	
    	this.events = new Events(serviceId);
    	        
		final ResourceConfig rc = new ResourceConfig()
        	.packages(NotFoundExceptionMapper.class.getPackage().getName())
        	.register(new SessionResource(hibernate, events))
        	.register(new HibernateRequestFilter(hibernate))
        	.register(new HibernateResponseFilter(hibernate))
        	.register(RolesAllowedDynamicFeature.class)
        	.register(new TokenRequestFilter());

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
        return GrizzlyHttpServerFactory.createHttpServer(URI.create(BASE_URI), rc);
    }

    /**
     * Main method.
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
    	
        final HttpServer server = new SessionStorage().startServer();
        System.out.println(String.format("Jersey app started with WADL available at "
                + "%sapplication.wadl\nHit enter to stop it...", BASE_URI));
        System.in.read();
        GrizzlyFuture<HttpServer> future = server.shutdown();
        try {
			future.get();
		} catch (InterruptedException | ExecutionException e) {
			logger.log(Level.WARNING, "server shutdown failed", e);
		}
        
        hibernate.getSessionFactory().close();
    }

	@Override
	public String getBaseUri() {
		return BASE_URI;
	}

	public static Hibernate getHibernate() {
		return hibernate;
	}

	@Override
	public void close() {
		events.close();
	}
}

