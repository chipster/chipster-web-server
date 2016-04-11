package fi.csc.chipster.servicelocator;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.servicelocator.resource.ServiceCatalog;
import fi.csc.chipster.servicelocator.resource.ServiceResource;

/**
 * Main class.
 *
 */
public class ServiceLocator {
	
	@SuppressWarnings("unused")
	private Logger logger = LogManager.getLogger();
	
	@SuppressWarnings("unused")
	private String serverId;

	private ServiceCatalog serviceCatalog;

	private AuthenticationClient authService;

	private Config config;

	private HttpServer httpServer;
	
	public ServiceLocator(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     */
    public void startServer() {
    	
    	String username = "serviceLocator";
    	String password = "serviceLocatorPassword";
    	String authUri = this.config.getString("authentication-service");
    	List<String> auths = Arrays.asList(authUri);
    	    	
    	this.authService = new AuthenticationClient(auths, username, password);    
    	
    	this.serverId = RestUtils.createId();
    	this.serviceCatalog = new ServiceCatalog();
    	
    	addService(Role.AUTHENTICATION_SERVICE, authUri);
    	addService(Role.FILE_BROKER, config.getString("file-broker"));
    	addService(Role.SCHEDULER, config.getString("scheduler"));
    	addService(Role.SESSION_DB, config.getString("session-db"));
    	addService(Role.SESSION_DB_EVENTS, config.getString("session-db-events"));
    	addService(Role.TOOLBOX, config.getString(Config.KEY_TOOLBOX_URL));
    	
    	// static configuration, discard updates
    	serviceCatalog.setReadOnly(true);
    	
    	TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
    	tokenRequestFilter.authenticationRequired(false);
    	        
    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
        	.register(new ServiceResource(serviceCatalog))
        	.register(tokenRequestFilter);
			//.register(new LoggingFilter())

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
    	URI baseUri = URI.create(this.config.getString("service-locator-bind"));
        this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
    }
    
    public void addService(String role, String uri) {
    	Service service = new Service(role, uri);
    	serviceCatalog.add(role, service);
    }

    /**
     * Main method.
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
    	
        final ServiceLocator server = new ServiceLocator(new Config());
        server.startServer();
        RestUtils.waitForShutdown("service locator", server.getHttpServer());
    }

	private HttpServer getHttpServer() {
		return this.httpServer;
	}

	public void close() {
		RestUtils.shutdown("service locator", httpServer);
	}
}

