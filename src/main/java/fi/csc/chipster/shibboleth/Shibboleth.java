package fi.csc.chipster.shibboleth;

import java.io.File;
import java.net.URI;
import java.util.Map;

import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.exception.ExceptionServletFilter;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;


public class Shibboleth {
		
	private Logger logger = LogManager.getLogger();
	
	private AuthenticationClient authService;
	private ServiceLocatorClient serviceLocator;
	private Config config;
	@SuppressWarnings("unused")
	private String serviceId;
	
	private Tomcat tomcat;

	public Shibboleth(Config config) {
		this.config = config;
	}

    /**
     * Starts a HTTP server exposing the REST resources defined in this application.
     * @return 
     * @throws Exception  
     */
    public void startServer() throws Exception {
    	
    	Map<String, String> ssoAccounts = config.getSsoServicePasswords();
    	
    	if (ssoAccounts.isEmpty()) {
    		throw new IllegalStateException("sso service username and password is not configured");
    	}
    	    	
    	String username = ssoAccounts.keySet().toArray(new String[0])[0];
    	String password = config.getSsoPassword(username);
    	
    	if (ssoAccounts.size() > 1) {
    		logger.warn(ssoAccounts.size() + " sso accounts configured, will pick one: " + username);
    	}
    	
    	this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);
		this.serviceLocator.setCredentials(authService.getCredentials());

    	URI baseUri = URI.create(this.config.getBindUrl(Role.SSO));
    	    
        tomcat = new Tomcat();
                
        // initialize tomcat with default nio connector
        Tomcat tomcat = new Tomcat();
        
        if ("ajp".equals(baseUri.getScheme())) {
        	
        	// create an AJP connector
        	Connector ajpConnector = new Connector("AJP/1.3");
        	ajpConnector.setPort(baseUri.getPort());
        	tomcat.getService().addConnector(ajpConnector);
        } else {
        	// http
        	tomcat.setPort(baseUri.getPort());
        }

        // the dir "webroot" isn't used and doesn't exist, but at least the tomcat doesn't share anything by accident
        Context ctx = tomcat.addContext("", new File("webroot").getAbsolutePath());

        Tomcat.addServlet(ctx, ShibbolethServlet.class.getSimpleName(), new ShibbolethServlet(this.authService, this.serviceLocator));
        ctx.addServletMappingDecoded("/*", ShibbolethServlet.class.getSimpleName());
        
        // add the exception filter
        FilterDef def = new FilterDef();
        def.setFilterName(ExceptionServletFilter.class.getSimpleName());
        def.setFilterClass(ExceptionServletFilter.class.getName());
        ctx.addFilterDef( def );
        FilterMap map = new FilterMap();
        map.setFilterName(ExceptionServletFilter.class.getSimpleName());
        map.addURLPattern( "/*" );
        ctx.addFilterMap(map);

        tomcat.start();
        
        logger.info(Role.SSO + " " + username + " started");
        
        //tomcat.getServer().await();        
    }

    /**
     * Main method.
     * @param args
     * @throws Exception 
     * @throws InterruptedException s
     */
    public static void main(String[] args) throws Exception {
    	Shibboleth service = new Shibboleth(new Config());
    	try {
    		service.startServer();
    		service.tomcat.getServer().await();
    		
    	} catch (Exception e) {
    		System.err.println("shibboleth service startup failed, exiting");
    		e.printStackTrace(System.err);
    		service.close();
    		System.exit(1);
    	}
    	
    	//TODO implement for Tomcat
//    	RestUtils.shutdownGracefullyOnInterrupt(
//    			service.server, 
//    			3000, 
//    			"shibboleth");	
    }

	public void close() {
		try {
			tomcat.stop();
		} catch (Exception e) {
			logger.warn("failed to stop the shibboleth service", e);
		}
	}
}
