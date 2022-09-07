package fi.csc.chipster.servicelocator;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.JerseyStatisticsSource;
import fi.csc.chipster.rest.LogType;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.resource.Service;
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

	private AuthenticationClient authService;

	private Config config;

	private HttpServer httpServer;

	private HttpServer adminServer;
	
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
    	this.authService = new AuthenticationClient(authUri, username, password, Role.SERVER);    
    	
    	this.serverId = RestUtils.createId();
    	
    	Map<String, String> intServices = config.getInternalServiceUrls();
    	Map<String, String> extServices = config.getExternalServiceUrls();
    	Map<String, String> adminServices = config.getAdminServiceUrls();
    	
    	// all services having internal, external or admin address
    	HashSet<String> services = new HashSet<>();
    	services.addAll(intServices.keySet());
    	services.addAll(extServices.keySet());
    	services.addAll(adminServices.keySet());
    	
    	ArrayList<Service> publicServices = new ArrayList<>();
    	ArrayList<Service> allServices = new ArrayList<>();
    	
    	for (String service : services) {
    		
    		publicServices.add(new Service(service, null, extServices.get(service), null));
    		
    		// map returns null for missing addresses
    		allServices.add(new Service(
    			service, 
    			intServices.get(service), 
    			extServices.get(service), 
    			adminServices.get(service)));    		    		
    	}
    	
    	TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
    	// clients can get the public services without authentication
    	tokenRequestFilter.addAllowedRole(Role.UNAUTHENTICATED);
    	// servers need to get the internal services with username and password to find the auth address and get a token
    	tokenRequestFilter.addAllowedRole(Role.PASSWORD);
    	
    	ServiceLocatorClient client = new LocalServiceLocatorClient(publicServices, allServices, config);
    	        
    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig(client)
			//.register(RestUtils.getLoggingFeature(Role.SERVICE_LOCATOR))
        	.register(new ServiceResource(publicServices, allServices))
        	.register(tokenRequestFilter);
    	
    	JerseyStatisticsSource jerseyStatisticsSource = RestUtils.createJerseyStatisticsSource(rc);
		
        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
    	URI baseUri = URI.create(this.config.getBindUrl(Role.SERVICE_LOCATOR));
        this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc, false);
        RestUtils.configureGrizzlyThreads(this.httpServer, Role.SERVICE_LOCATOR, false, config);
        RestUtils.configureGrizzlyRequestLog(this.httpServer, Role.SERVICE_LOCATOR, LogType.API);
                
        jerseyStatisticsSource.collectConnectionStatistics(httpServer);
        
        this.httpServer.start();
                
        this.adminServer = RestUtils.startAdminServer(Role.SERVICE_LOCATOR, config, authService, client, jerseyStatisticsSource);
    }

    /**
     * Main method.
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
    	
        final ServiceLocator service = new ServiceLocator(new Config());
        
        RestUtils.shutdownGracefullyOnInterrupt(service.getHttpServer(), Role.SERVICE_LOCATOR);
        
        service.startServer();
        RestUtils.waitForShutdown("service locator", service.getHttpServer());
    }

	private HttpServer getHttpServer() {
		return this.httpServer;
	}

	public void close() {
		RestUtils.shutdown("service-locator-admin", adminServer);
		RestUtils.shutdown("service-locator", httpServer);
	}
}

