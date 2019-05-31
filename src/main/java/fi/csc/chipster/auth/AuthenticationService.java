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

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.Token;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.resource.AuthUserResource;
import fi.csc.chipster.auth.resource.AuthenticationRequestFilter;
import fi.csc.chipster.auth.resource.OidcResource;
import fi.csc.chipster.auth.resource.SsoTokenResource;
import fi.csc.chipster.auth.resource.TokenResource;
import fi.csc.chipster.auth.resource.TokenTable;
import fi.csc.chipster.auth.resource.UserTable;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.JerseyStatisticsSource;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.token.TokenRequestFilter;
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

	private HttpServer ssoHttpServer;
	
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
    public void startServer() throws IOException, IllegalConfigurationException, InterruptedException, URISyntaxException {    	    	
    	
    	// init Hibernate
    	List<Class<?>> hibernateClasses = Arrays.asList(new Class<?>[] { 
    		Token.class,
    		User.class,
    	});
    	
    	hibernate = new HibernateUtil(config, Role.AUTH, hibernateClasses);    	
    	
    	TokenTable tokenTable = new TokenTable(hibernate);
    	UserTable userTable = new UserTable(hibernate);
    	
    	TokenResource tokenResource = new TokenResource(tokenTable, userTable);
    	OidcResource oidcResource = new OidcResource(tokenTable, userTable, config);
    	AuthUserResource userResource = new AuthUserResource(userTable);
    	AuthenticationRequestFilter authRequestFilter = new AuthenticationRequestFilter(hibernate, config, userTable);

    	final ResourceConfig rc = RestUtils.getDefaultResourceConfig()        	
        	.register(tokenResource)
        	.register(oidcResource)
        	.register(userResource)
        	.register(new HibernateRequestFilter(hibernate))
        	.register(new HibernateResponseFilter(hibernate))
        	//.register(new LoggingFilter())
        	.register(authRequestFilter);
    	
    	JerseyStatisticsSource jerseyStatisticsSource = RestUtils.createJerseyStatisticsSource(rc);
		AdminResource adminResource = new AdminResource(hibernate, Token.class, jerseyStatisticsSource);

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
    	URI baseUri = URI.create(this.config.getBindUrl(Role.AUTH));
        this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc, false);

        jerseyStatisticsSource.collectConnectionStatistics(httpServer);
        
        this.httpServer.start();
        
        /* 
         * Authenticate admin API using the same Rest API that all other admin APIs are using
         * even if it is running in this same process. We have to set the address explicitly, because
         * ServiceLocator isn't running yet.
         */ 
        
        URL bindUrl = URI.create(config.getBindUrl(Role.AUTH)).toURL();
        String localhostUrl = new URL(bindUrl.getProtocol(), "localhost", bindUrl.getPort(), bindUrl.getFile()).toString();

        AuthenticationClient authClient = new AuthenticationClient(localhostUrl, Role.AUTH, config.getPassword(Role.AUTH));
		this.adminServer = RestUtils.startAdminServer(
        		adminResource, hibernate, 
        		Role.AUTH, config, authClient);
		
		// separate port for the sso tokens, but only if configured explicitly
		
		String ssoBindUrlString = config.getM2mBindUrl(Role.AUTH);
		
		if (ssoBindUrlString != null && !ssoBindUrlString.isEmpty()) {
			this.ssoHttpServer = enableSsoLogins(tokenTable, userTable, authClient, ssoBindUrlString);
		}
    }

	private HttpServer enableSsoLogins(TokenTable tokenTable, UserTable userTable, AuthenticationClient authClient,
			String ssoBindUrlString) throws IOException {
		
        final ResourceConfig ssoRc = RestUtils.getDefaultResourceConfig()        	
            	.register(new TokenRequestFilter(authClient))
            	.register(new SsoTokenResource(config, tokenTable, userTable));
        
    	ssoRc.register(new HibernateRequestFilter(hibernate))
    		.register(new HibernateResponseFilter(hibernate));

        HttpServer ssoHttpServer = GrizzlyHttpServerFactory.createHttpServer(URI.create(ssoBindUrlString), ssoRc);
        ssoHttpServer.start();
        
        return ssoHttpServer;
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
    public static void main(String[] args) throws IOException, IllegalConfigurationException, InterruptedException, SQLException, URISyntaxException {
    	
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
		RestUtils.shutdown("auth-sso", ssoHttpServer);
		RestUtils.shutdown("auth", httpServer);
		hibernate.getSessionFactory().close();
	}
}

