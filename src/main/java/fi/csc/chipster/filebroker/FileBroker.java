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
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketClosedException;
import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketErrorException;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDb;
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

	private FileResource fileResource;

	public FileBroker(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     * @throws RestException 
     * @throws IOException 
     * @throws InterruptedException 
     * @throws WebSocketClosedException 
     * @throws WebSocketErrorException 
     */
    public void startServer() throws RestException, IOException {
    	
    	String username = Config.USERNAME_FILE_BROKER;
    	String password = config.getPassword(username);    	
    	
    	this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);
		this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials());		
    	
		File storage = new File("storage");
		storage.mkdir();    
		this.fileResource = new FileResource(storage, sessionDbClient);
		sessionDbClient.subscribe(SessionDb.FILES_TOPIC, fileResource, "file-broker-file-listener");
    	
    	TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
    	        
    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
        	.register(fileResource)
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
     * @throws RestException 
     * @throws IOException
     * @throws InterruptedException 
     */
    public static void main(String[] args) throws RestException, IOException {
    	
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

