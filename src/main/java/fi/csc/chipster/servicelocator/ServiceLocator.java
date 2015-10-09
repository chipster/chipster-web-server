package fi.csc.chipster.servicelocator;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.Server;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.servicelocator.resource.ServiceCatalog;
import fi.csc.chipster.servicelocator.resource.ServiceResource;
import fi.csc.chipster.sessiondb.resource.Events;

/**
 * Main class.
 *
 */
public class ServiceLocator implements Server {
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(ServiceLocator.class.getName());
	
	private String serverId;

	private Events events;

	private ServiceCatalog serviceCatalog;

	private AuthenticationClient authService;

	private Config config;
	
	public ServiceLocator(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     */
    @Override
    public HttpServer startServer() {
    	
    	String username = "serviceLocator";
    	String password = "serviceLocatorPassword";
    	String authUri = this.config.getString("authentication-service");
    	List<String> auths = Arrays.asList(new String[] { authUri });
    	    	
    	this.authService = new AuthenticationClient(auths, username, password);    
    	
    	this.serverId = RestUtils.createId();
    	this.events = new Events(serverId);
    	this.serviceCatalog = new ServiceCatalog();
    	
    	Service auth = new Service(Role.AUTHENTICATION_SERVICE, authUri);
    	serviceCatalog.add(Role.AUTHENTICATION_SERVICE, auth);
    	
    	TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
    	tokenRequestFilter.authenticationRequired(false);
    	        
    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
        	.register(new ServiceResource(serviceCatalog, events))
        	.register(tokenRequestFilter);
			//.register(new LoggingFilter())

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
        return GrizzlyHttpServerFactory.createHttpServer(URI.create(getBaseUri()), rc);
    }

    /**
     * Main method.
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
    	
        final HttpServer server = new ServiceLocator(new Config()).startServer();
        RestUtils.waitForShutdown("service locator", server);
    }

	@Override
	public void close() {
		events.close();
	}

	@Override
	public String getBaseUri() {
		return this.config.getString("service-locator-bind");
	}
}

