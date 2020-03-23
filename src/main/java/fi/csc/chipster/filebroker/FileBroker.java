package fi.csc.chipster.filebroker;

import java.net.URI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.JerseyStatisticsSource;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.SessionDbClient;


public class FileBroker {
		
	@SuppressWarnings("unused")
	private Logger logger = LogManager.getLogger();
	
	private AuthenticationClient authService;
	private ServiceLocatorClient serviceLocator;
	private Config config;
	@SuppressWarnings("unused")
	private String serviceId;
	
	private SessionDbClient sessionDbClient;

	private HttpServer adminServer;

	private HttpServer httpServer;

	private FileBrokerResource fileBrokerResource;

	private StorageDiscovery storageDiscovery;

	public FileBroker(Config config) {
		this.config = config;
	}

    /**
     * Starts a HTTP server exposing the REST resources defined in this application.
     * @return 
     * @throws Exception  
     */
    public void startServer() throws Exception {
    	
    	String username = Role.FILE_BROKER;
    	String password = config.getPassword(username);    	
    	
    	this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());
		
		this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());
		
		this.storageDiscovery = new StorageDiscovery(this.serviceLocator, authService, config);		
		this.fileBrokerResource = new FileBrokerResource(this.serviceLocator, this.sessionDbClient, storageDiscovery);
                
    	TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
    	tokenRequestFilter.authenticationRequired(false, false);
    	        
    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig(serviceLocator)
			//.register(RestUtils.getLoggingFeature(Role.FILE_STORAGE))
        	.register(fileBrokerResource)
        	.register(tokenRequestFilter);
    	
    	JerseyStatisticsSource jerseyStatisticsSource = RestUtils.createJerseyStatisticsSource(rc);
		
        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
    	URI baseUri = URI.create(this.config.getBindUrl(Role.FILE_BROKER));
        this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc, false);
                
        jerseyStatisticsSource.collectConnectionStatistics(httpServer);
        
        this.httpServer.start();
        
        FileBrokerAdminResource adminResource = new FileBrokerAdminResource(jerseyStatisticsSource, storageDiscovery, sessionDbClient);
		this.adminServer = RestUtils.startAdminServer(adminResource, null, Role.FILE_BROKER, config, authService, this.serviceLocator);
    }


	/**
     * Main method.
     * @param args
     * @throws Exception 
     * @throws InterruptedException s
     */
    public static void main(String[] args) throws Exception {
    	FileBroker fileBroker = new FileBroker(new Config());
    	try {
    		fileBroker.startServer();
    	} catch (Exception e) {
    		System.err.println("file-broker startup failed, exiting");
    		e.printStackTrace(System.err);
    		fileBroker.close();
    		System.exit(1);
    	}
    	RestUtils.shutdownGracefullyOnInterrupt(
    			fileBroker.httpServer, 
    			fileBroker.config.getInt(Config.KEY_FILE_BROKER_SHUTDOWN_TIMEOUT), 
    			"file-broker");	
    }
    
	public void close() {
		RestUtils.shutdown("file-broker-admin", adminServer);
		RestUtils.shutdown("file-broker", httpServer);
	}
}

