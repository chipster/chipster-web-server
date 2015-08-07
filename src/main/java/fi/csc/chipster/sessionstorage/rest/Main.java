package fi.csc.chipster.sessionstorage.rest;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.sessionstorage.model.Dataset;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.chipster.sessionstorage.model.SessionDatasets;

/**
 * Main class.
 *
 */
public class Main {
	
	private static Logger logger = Logger.getLogger(Main.class.getName());
	
    // Base URI the Grizzly HTTP server will listen on
    public static final String BASE_URI = "http://localhost:8080/sessionstorage/";

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     */
    public static HttpServer startServer() {
    	
    	// show jersey logs in console
    	Logger l = Logger.getLogger(HttpHandler.class.getName());
    	l.setLevel(Level.FINE);
    	l.setUseParentHandlers(false);
    	ConsoleHandler ch = new ConsoleHandler();
    	ch.setLevel(Level.ALL);
    	l.addHandler(ch);
    	
    	List<Class<?>> hibernateClasses = Arrays.asList(new Class<?>[] {
    			Session.class,
    			Dataset.class,
    			SessionDatasets.class,
//    			Job.class,
//    			SessionJobs.class,
    	});
    	
    	// init Hibernate
    	Hibernate.buildSessionFactory(hibernateClasses);
    	
        // create a resource config that scans for JAX-RS resources and providers
    	String jaxPackage = SessionResource.class.getPackage().getName();
    	
    	logger.info("scanning JAX-RS resources from " + jaxPackage);
        final ResourceConfig rc = new ResourceConfig().packages(jaxPackage);

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
        return GrizzlyHttpServerFactory.createHttpServer(URI.create(BASE_URI), rc);
    }

    /**
     * Main method.
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
    	
        final HttpServer server = startServer();
        System.out.println(String.format("Jersey app started with WADL available at "
                + "%sapplication.wadl\nHit enter to stop it...", BASE_URI));
        System.in.read();
        GrizzlyFuture<HttpServer> future = server.shutdown();
        try {
			future.get();
		} catch (InterruptedException | ExecutionException e) {
			logger.log(Level.WARNING, "server shutdown failed", e);
		}
        
        Hibernate.getSessionFactory().close();
    }
}

