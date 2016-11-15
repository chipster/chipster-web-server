package fi.csc.chipster.proxy;

import java.net.URI;
import java.net.URISyntaxException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

/**
 * Chipster specific version of the ProxyServer
 * 
 * Uses Chipster configuration and infrastructure to set up the proxy 
 * and a simple management Rest API for it.
 * 
 * @author klemela
 *
 */
public class ChipsterProxyServer {
	
	private final Logger logger = LogManager.getLogger();
	
    private ProxyServer proxy;

	private ServiceLocatorClient serviceLocator;
	private AuthenticationClient authService;
	@SuppressWarnings("unused")
	private String serviceId;

	private HttpServer restServer;

	private Config config;

	private ChipsterProxyAdminResource adminResource;
    
    public static void main(String[] args) {
    	ChipsterProxyServer server = new ChipsterProxyServer(new Config());
    	server.startServer();
    	RestUtils.waitForShutdown("proxy", null);
    	server.close();
    }

	public ChipsterProxyServer(Config config) {
    	this.config = config;
    	this.proxy = new ProxyServer(URI.create(config.getString("proxy-bind")));
    	
    	try {
			proxy.addRoute("sessiondb", 		config.getString("session-db"));
			proxy.addRoute("sessiondbevents", 	config.getString("session-db-events"));
			proxy.addRoute("auth", 				config.getString("authentication-service"));
			proxy.addRoute("filebroker", 		config.getString("file-broker"));
			proxy.addRoute("toolbox", 			config.getString(Config.KEY_TOOLBOX_URL));
			// it's possible to use the web server through the proxy, but let's
			// have it in a separate port for now to find any CORS issues
			//proxy.addRoute("",	 				config.getString("web"));

			//proxy.addRoute("test", 				"http://vm0180.kaj.pouta.csc.fi:8081/");
			
    	} catch (URISyntaxException e) {
    		logger.error("proxy configuration error", e);
    	}
    }
	
    private void startAdminAPI() {

		String username = Config.USERNAME_PROXY;
		String password = config.getPassword(username);

		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);
		this.adminResource = new ChipsterProxyAdminResource(proxy);
		
		final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
				//.register(new LoggingFilter())
				.register(new TokenRequestFilter(authService))
				.register(adminResource);

		restServer = GrizzlyHttpServerFactory.createHttpServer(config.getURI("proxy-admin-bind"), rc);
	}

	
	public void startServer() {
		proxy.startServer();
		startAdminAPI();
	}
	
	public void close() {
		RestUtils.shutdown("proxy-admin", restServer);
		proxy.close();
	}
}
