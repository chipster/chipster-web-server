package fi.csc.chipster.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map.Entry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
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

	private HttpServer adminServer;

	private Config config;
    
    public static void main(String[] args) throws IOException {
    	ChipsterProxyServer server = new ChipsterProxyServer(new Config());
    	server.startServer();
    	RestUtils.waitForShutdown("proxy", null);
    	server.close();
    }

	public ChipsterProxyServer(Config config) {
    	this.config = config;
    	try {
    	this.proxy = new ProxyServer(URI.create(config.getBindUrl(Role.PROXY)));
    	
    		for (Entry<String, String> entry : config.getExternalServiceUrls().entrySet()) {
    			
    			String service = entry.getKey();
    			String externalAddress = entry.getValue();
    			
    			if (Role.PROXY.equals(service)) {
    				// no point to proxy ourselves
    				continue;
    			}
    			
    			if (Role.WEB_SERVER.equals(service)) {
    				// it's possible to use the web server through the proxy, but let's
    				// have it in a separate port for now to find any CORS issues
    				continue;
    			}
    			
    			proxy.addRoute(service.replace("-", ""), externalAddress);
    		}
    		
			//proxy.addRoute("test", 				"http://vm0180.kaj.pouta.csc.fi:8081/");
    		
			
    	} catch (URISyntaxException e) {
    		logger.error("proxy configuration error", e);
    	}
    }
	
    private void startAdminAPI() throws IOException {

		String username = Role.PROXY;
		String password = config.getPassword(username);

		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);
		
		// separate port for the proxy admin api		
		adminServer = RestUtils.startAdminServer(new ChipsterProxyAdminResource(proxy), null, Role.PROXY, config, authService);
	}

	
	public void startServer() throws IOException {
		proxy.startServer();
		startAdminAPI();
	}
	
	public void close() {
		RestUtils.shutdown("proxy-admin", adminServer);
		proxy.close();
	}
}
