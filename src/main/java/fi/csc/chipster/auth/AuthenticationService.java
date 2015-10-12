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

import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.resource.AuthenticationRequestFilter;
import fi.csc.chipster.auth.resource.TokenResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;

/**
 * Main class.
 *
 */
public class AuthenticationService {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private static HibernateUtil hibernate;

	private Config config;

	private HttpServer httpServer;
	
	public AuthenticationService(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     */
    public void startServer() {
    	    	
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
    	URI baseUri = URI.create(this.config.getString("authentication-service-bind"));
        this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
    }

    /**
     * Main method.
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
    	
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
		RestUtils.shutdown(httpServer);
	}
}

