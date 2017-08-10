package fi.csc.chipster.servicelocator;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.GenericAdminResource;
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
     * @throws IOException 
     */
    public void startServer() throws IOException {
    	
    	String username = Role.SERVICE_LOCATOR;
    	String password = config.getPassword(username);
    	String authUri = this.config.getInternalServiceUrls().get(Role.AUTH);
    	List<String> auths = Arrays.asList(authUri);    	
    	this.authService = new AuthenticationClient(auths, username, password);    
    	
    	this.serverId = RestUtils.createId();
    	this.serviceCatalog = new ServiceCatalog();
    	
    	Map<String, String> intServices = config.getInternalServiceUrls();
    	Map<String, String> extServices = config.getExternalServiceUrls();
    	
    	// all services having internal or external address
    	HashSet<String> services = new HashSet<>();
    	services.addAll(intServices.keySet());
    	services.addAll(extServices.keySet());
    	
    	for (String service : services) {    		
    		// map returns null for missing addresses
    		addService(service, intServices.get(service), extServices.get(service));
    	}
    	
    	// static configuration, discard updates
    	serviceCatalog.setReadOnly(true);
    	
    	TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
    	tokenRequestFilter.authenticationRequired(false, false);
    	        
    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
        	.register(new ServiceResource(serviceCatalog))
        	.register(new GenericAdminResource())
        	.register(tokenRequestFilter);
			//.register(new LoggingFilter())

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
    	URI baseUri = URI.create(this.config.getBindUrl(Role.SERVICE_LOCATOR));
        this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
    }
    
    public void addService(String role, String uri, String publicUri) {
    	Service service = new Service(role, uri, publicUri);
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

