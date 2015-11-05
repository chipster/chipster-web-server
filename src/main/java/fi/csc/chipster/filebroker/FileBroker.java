package fi.csc.chipster.filebroker;

import java.io.File;
import java.io.IOException;
import java.net.URI;

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
	
	private HttpServer httpServer;

	private SessionDbClient sessionDbClient;

	public FileBroker(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     */
    public void startServer() {
    	
    	String username = "fileBroker";
    	String password = "fileBrokerPassword";
    	
    	this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);
		this.serviceId = serviceLocator.register(Role.FILE_BROKER, authService, config.getString("file-broker-bind"));
		this.sessionDbClient = new SessionDbClient(serviceLocator, authService);
    	
    	File storage = new File("storage");
    	storage.mkdir();    
    	
    	TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
    	        
    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
        	.register(new FileResource(storage, sessionDbClient))
        	//.register(new LoggingFilter())
        	.register(tokenRequestFilter);

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
    	URI baseUri = URI.create(this.config.getString("file-broker-bind"));
        this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
    }

    /**
     * Main method.
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
    	
        final FileBroker server = new FileBroker(new Config());
        server.startServer();
        RestUtils.waitForShutdown("file-broker", server.getHttpServer());
    }

	private HttpServer getHttpServer() {
		return this.httpServer;
	}

	public void close() {
		RestUtils.shutdown("file-broker", httpServer);
	}
}

