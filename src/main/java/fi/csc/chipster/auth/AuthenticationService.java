package fi.csc.chipster.auth;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.resource.AuthenticationRequestFilter;
import fi.csc.chipster.auth.resource.TokenResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.microarray.config.ConfigurationLoader.IllegalConfigurationException;

/**
 * Main class.
 *
 */
public class AuthenticationService {
	
	@SuppressWarnings("unused")
	private Logger logger = LogManager.getLogger();

	private static HibernateUtil hibernate;

	private Config config;

	private HttpServer httpServer;

	private HttpServer adminServer;
	
	public AuthenticationService(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     * @throws IOException 
     * @throws IllegalConfigurationException 
     */
    public void startServer() throws IOException, IllegalConfigurationException {
    	    	
    	List<Class<?>> hibernateClasses = Arrays.asList(new Class<?>[] {
    			Token.class,
    	});
    	
    	// init Hibernate
    	hibernate = new HibernateUtil("update");
    	hibernate.buildSessionFactory(hibernateClasses, "chipster-auth-db");
    	
    	TokenResource authResource = new TokenResource(hibernate);
    	AuthenticationRequestFilter authRequestFilter = new AuthenticationRequestFilter(hibernate, config);

    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig()        	
        	.register(authResource)
        	.register(new HibernateRequestFilter(hibernate))
        	.register(new HibernateResponseFilter(hibernate))
        	//.register(new LoggingFilter())
        	.register(authRequestFilter);
    	
    	AdminResource adminResource = new AdminResource(hibernate, Token.class, RestUtils.createJerseyStatisticsSource(rc));

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
    	URI baseUri = URI.create(this.config.getBindUrl(Role.AUTH));
        this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
        
        /* 
         * Authenticate admin API using the same Rest API that all other admin APIs are using
         * even if it is running in this same process. We have to set the address explicitly, because
         * ServiceLocator isn't running yet.
         */ 
        AuthenticationClient authClient = new AuthenticationClient(config.getBindUrl(Role.AUTH), Role.AUTH, config.getPassword(Role.AUTH));
		this.adminServer = RestUtils.startAdminServer(
        		adminResource, hibernate, 
        		Role.AUTH, config, authClient);

    }

    /**
     * Main method.
     * @param args
     * @throws IOException
     * @throws IllegalConfigurationException 
     */
    public static void main(String[] args) throws IOException, IllegalConfigurationException {
    	
        final AuthenticationService server = new AuthenticationService(new Config());
        server.startServer();
        RestUtils.waitForShutdown("authentication service", server.getHttpServer());
        
        hibernate.getSessionFactory().close();
    }
	
	private HttpServer getHttpServer() {
		return httpServer;
	}

	public HibernateUtil getHibernate() {
		return hibernate;
	}
	
	public void close() {
		RestUtils.shutdown("auth-admin", adminServer);
		RestUtils.shutdown("auth", httpServer);
	}
}

