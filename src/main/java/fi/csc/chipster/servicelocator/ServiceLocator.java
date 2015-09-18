package fi.csc.chipster.servicelocator;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

import fi.csc.chipster.auth.AuthenticationService;
import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.CORSResponseFilter;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.Server;
import fi.csc.chipster.rest.exception.NotFoundExceptionMapper;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.servicelocator.resource.ServiceCatalog;
import fi.csc.chipster.servicelocator.resource.ServiceResource;
import fi.csc.chipster.sessionstorage.resource.Events;

/**
 * Main class.
 *
 */
public class ServiceLocator implements Server {
	
	private static Logger logger = Logger.getLogger(ServiceLocator.class.getName());
	
    // Base URI the Grizzly HTTP server will listen on
    public static final String BASE_URI = "http://0.0.0.0:8082/servicelocator/";

	private String serverId;

	private Events events;

	private ServiceCatalog serviceCatalog;

	private AuthenticationClient authService;

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     */
    @Override
    public HttpServer startServer() {
    	
    	String username = "serviceLocator";
    	String password = "serviceLocatorPassword";
    	List<String> auths = Arrays.asList(new String[] { AuthenticationService.BASE_URI });
    	    	
    	this.authService = new AuthenticationClient(auths, username, password);
    	
    	// show jersey logs in console
    	Logger l = Logger.getLogger(HttpHandler.class.getName());
    	l.setLevel(Level.FINE);
    	l.setUseParentHandlers(false);
    	ConsoleHandler ch = new ConsoleHandler();
    	ch.setLevel(Level.ALL);
    	l.addHandler(ch);
    	
    	this.serverId = RestUtils.createId();
    	this.events = new Events(serverId);
    	this.serviceCatalog = new ServiceCatalog();
    	
    	//FIXME make configurable
    	Service auth = new Service(Role.AUTHENTICATION_SERVICE, AuthenticationService.BASE_URI);
    	serviceCatalog.add(Role.AUTHENTICATION_SERVICE, auth);
    	
    	TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
    	tokenRequestFilter.authenticationRequired(false);
    	        
		final ResourceConfig rc = new ResourceConfig()
        	.packages(NotFoundExceptionMapper.class.getPackage().getName())
        	.register(new ServiceResource(serviceCatalog, events))
        	.register(RolesAllowedDynamicFeature.class)
        	.register(CORSResponseFilter.class)
        	.register(tokenRequestFilter);

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
    	
        final HttpServer server = new ServiceLocator().startServer();
        System.out.println(String.format("Jersey app started with WADL available at "
                + "%sapplication.wadl\nHit enter to stop it...", BASE_URI));
        System.in.read();
        GrizzlyFuture<HttpServer> future = server.shutdown();
        try {
			future.get();
		} catch (InterruptedException | ExecutionException e) {
			logger.log(Level.WARNING, "server shutdown failed", e);
		}
    }

	@Override
	public String getBaseUri() {
		return BASE_URI;
	}

	@Override
	public void close() {
		events.close();
	}
}

