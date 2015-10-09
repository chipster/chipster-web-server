package fi.csc.chipster.auth;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.resource.AuthenticationRequestFilter;
import fi.csc.chipster.auth.resource.TokenResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.Server;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;

/**
 * Main class.
 *
 */
public class AuthenticationService implements Server {
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(AuthenticationService.class.getName());

	private static HibernateUtil hibernate;

	private Config config;
	
	public AuthenticationService(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     */
    public HttpServer startServer() {
    	    	
    	List<Class<?>> hibernateClasses = Arrays.asList(new Class<?>[] {
    			Token.class,
    	});
    	
    	// init Hibernate
    	hibernate = new HibernateUtil();
    	hibernate.buildSessionFactory(hibernateClasses, "chipster-auth-db");
    	
    	TokenResource authResource = new TokenResource(hibernate);
    	
    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig()        	
        	.register(authResource)
        	.register(new HibernateRequestFilter(hibernate))
        	.register(new HibernateResponseFilter(hibernate))
        	.register(new AuthenticationRequestFilter(hibernate));
        	//.register(new LoggingFilter())

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
        return GrizzlyHttpServerFactory.createHttpServer(URI.create(getBaseUri()), rc);
    }

    /**
     * Main method.
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
    	
        final HttpServer server = new AuthenticationService(new Config()).startServer();
        RestUtils.waitForShutdown("authentication service", server);
        
        hibernate.getSessionFactory().close();
    }
	
	public HibernateUtil getHibernate() {
		return hibernate;
	}
	
	public void close() {
	}

	@Override
	public String getBaseUri() {
		return this.config.getString("authentication-service-bind");
	}
}

