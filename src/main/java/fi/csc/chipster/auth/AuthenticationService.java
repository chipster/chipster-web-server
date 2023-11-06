package fi.csc.chipster.auth;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.jaas.JaasAuthenticationProvider;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.resource.AuthAdminResource;
import fi.csc.chipster.auth.resource.AuthTokenResource;
import fi.csc.chipster.auth.resource.AuthTokens;
import fi.csc.chipster.auth.resource.AuthUserResource;
import fi.csc.chipster.auth.resource.AuthenticationRequestFilter;
import fi.csc.chipster.auth.resource.OidcProvidersImpl;
import fi.csc.chipster.auth.resource.OidcResource;
import fi.csc.chipster.auth.resource.UserTable;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.JerseyStatisticsSource;
import fi.csc.chipster.rest.LogType;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

/**
 * Main class.
 *
 */
public class AuthenticationService {
	
	private static final String KEY_JAAS_CONF_PATH = "auth-jaas-conf-path";
	
	private Logger logger = LogManager.getLogger();

	private static HibernateUtil hibernate;

	private Config config;

	private HttpServer httpServer;

	private HttpServer adminServer;

	private JaasAuthenticationProvider jaasAuthProvider;
	
	public static List<Class<?>> hibernateClasses = Arrays.asList(new Class<?>[] { 
		User.class,
	});
	
	public AuthenticationService(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     * @throws IOException 
     * @throws IllegalConfigurationException 
     * @throws InterruptedException 
     * @throws URISyntaxException 
     * @throws SQLException 
     */
    public void startServer() throws IOException, InterruptedException, URISyntaxException {    	    	
    	
    	// for some reason Hibernate now initializes the JAAS ConfigFile class, so make sure we have configured 
    	// the JAAS config file path system property before that
    	String jaasConfPath = config.getString(KEY_JAAS_CONF_PATH);
		if (jaasConfPath.isEmpty()) {
			// load default from the jar to avoid handling extra files in deployment scripts
			jaasConfPath = ClassLoader.getSystemClassLoader().getResource("jaas.config").toString();
		}
		logger.info("load JAAS config from " + jaasConfPath);
    	jaasAuthProvider = new JaasAuthenticationProvider(jaasConfPath);
    	
    	// init Hibernate
    	hibernate = new HibernateUtil(config, Role.AUTH, hibernateClasses);    	
    	UserTable userTable = new UserTable(hibernate);
    	AuthTokens authTokens = new AuthTokens(config);
    	
    	AuthTokenResource tokenResource = new AuthTokenResource(authTokens, userTable);
    	OidcResource oidcResource = new OidcResource(new OidcProvidersImpl(authTokens, userTable, config));
    	oidcResource.init(authTokens, userTable, config);
    	AuthUserResource userResource = new AuthUserResource(userTable);
    	AuthenticationRequestFilter authRequestFilter = new AuthenticationRequestFilter(hibernate, config, userTable, authTokens, jaasAuthProvider);
    	
    	ServiceLocatorClient serviceLocator = new ServiceLocatorClient(config);

    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig(serviceLocator)        	
        	.register(tokenResource)
        	.register(oidcResource)
        	.register(userResource)
        	.register(new HibernateRequestFilter(hibernate))
        	.register(new HibernateResponseFilter(hibernate))
        	//.register(new LoggingFilter())
        	.register(authRequestFilter);
    	
    	JerseyStatisticsSource jerseyStatisticsSource = RestUtils.createJerseyStatisticsSource(rc);
		
    	AuthAdminResource authAdminResource = new AuthAdminResource(hibernate, hibernateClasses, jerseyStatisticsSource, userTable);

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
    	URI baseUri = URI.create(this.config.getBindUrl(Role.AUTH));
        this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc, false);
        RestUtils.configureGrizzlyThreads(httpServer, Role.AUTH, false, config);
        RestUtils.configureGrizzlyRequestLog(this.httpServer, Role.AUTH, LogType.API);

        jerseyStatisticsSource.collectConnectionStatistics(httpServer);
        
        this.httpServer.start();
        
        /* 
         * Authenticate admin API using the same Rest API that all other admin APIs are using
         * even if it is running in this same process. We have to set the address explicitly, because
         * ServiceLocator isn't running yet.
         */ 
        
        URL bindUrl = URI.create(config.getBindUrl(Role.AUTH)).toURL();
        String localhostUrl = new URL(bindUrl.getProtocol(), "localhost", bindUrl.getPort(), bindUrl.getFile()).toString();

        AuthenticationClient authClient = new AuthenticationClient(localhostUrl, Role.AUTH, config.getPassword(Role.AUTH), Role.SERVER);
		this.adminServer = RestUtils.startAdminServer(
        		authAdminResource, hibernate, 
        		Role.AUTH, config, authClient, serviceLocator);
    }

    /**
     * Main method.
     * @param args
     * @throws IOException
     * @throws IllegalConfigurationException 
     * @throws InterruptedException 
     * @throws SQLException 
     * @throws URISyntaxException 
     */
    public static void main(String[] args) throws IOException, InterruptedException, SQLException, URISyntaxException {
    	
        final AuthenticationService service = new AuthenticationService(new Config());
        service.startServer();
        
        RestUtils.shutdownGracefullyOnInterrupt(service.getHttpServer(), Role.AUTH);
        
        RestUtils.waitForShutdown("authentication service", service.getHttpServer());
        
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
		hibernate.getSessionFactory().close();
	}
}

