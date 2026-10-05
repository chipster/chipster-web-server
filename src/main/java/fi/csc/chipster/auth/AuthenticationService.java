package fi.csc.chipster.auth;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.jaas.JaasAuthenticationProvider;
import fi.csc.chipster.auth.jaas.UsersFile;
import fi.csc.chipster.auth.model.OidcLoginSession;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.oidc.OidcProvidersImpl;
import fi.csc.chipster.auth.oidc.loginsessions.OidcLoginSessions;
import fi.csc.chipster.auth.oidc.loginsessions.OidcLoginSessionsInDb;
import fi.csc.chipster.auth.oidc.loginsessions.OidcLoginSessionsInMemory;
import fi.csc.chipster.auth.resource.AuthAdminResource;
import fi.csc.chipster.auth.resource.AuthTokenResource;
import fi.csc.chipster.auth.resource.AuthTokens;
import fi.csc.chipster.auth.resource.AuthUserResource;
import fi.csc.chipster.auth.resource.AuthenticationRequestFilter;
import fi.csc.chipster.auth.resource.OidcResource;
import fi.csc.chipster.auth.resource.UserTable;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.JerseyStatisticsSource;
import fi.csc.chipster.rest.LogType;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServerComponent;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

/**
 * Main class.
 *
 */
public class AuthenticationService implements ServerComponent {

    private static final String KEY_JAAS_CONF_PATH = "auth-jaas-conf-path";
    // package-private for the tests
    static final String KEY_ALLOW_DEFAULT_PASSWORDS = "auth-allow-default-passwords";
    private static final String KEY_OIDC_SESSION_IN_DB = "auth-oidc-session-in-db";

    private Logger logger = LogManager.getLogger();

    private static HibernateUtil hibernate;

    private Config config;

    private HttpServer httpServer;

    private HttpServer adminServer;

    private JaasAuthenticationProvider jaasAuthProvider;

    private AuthenticationClient adminAuthClient;

    public static List<Class<?>> hibernateClasses = Arrays.asList(new Class<?>[] {
            User.class,
            OidcLoginSession.class,
    });

    public AuthenticationService(Config config) {
        this.config = config;
    }

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
     * application.
     * 
     * @return Grizzly HTTP server.
     * @throws IOException
     * @throws IllegalConfigurationException
     * @throws InterruptedException
     * @throws URISyntaxException
     * @throws SQLException
     */
    public void startServer() throws IOException, InterruptedException, URISyntaxException {

        checkDefaultPasswords();

        // for some reason Hibernate now initializes the JAAS ConfigFile class, so make
        // sure we have configured
        // the JAAS config file path system property before that
        String jaasConfPath = config.getString(KEY_JAAS_CONF_PATH);
        if (jaasConfPath.isEmpty()) {
            // load default from the jar to avoid handling extra files in deployment scripts
            jaasConfPath = ClassLoader.getSystemClassLoader().getResource("jaas.config").toString();
        }
        logger.info("load JAAS config from " + jaasConfPath);
        jaasAuthProvider = new JaasAuthenticationProvider(jaasConfPath);

        checkUsersFiles(jaasAuthProvider.getPasswordFiles());

        ServiceLocatorClient serviceLocator = new ServiceLocatorClient(config);

        // init Hibernate
        hibernate = new HibernateUtil(config, Role.AUTH, hibernateClasses);
        UserTable userTable = new UserTable(hibernate);
        AuthTokens authTokens = new AuthTokens(config);

        AuthTokenResource tokenResource = new AuthTokenResource(authTokens, userTable);

        OidcLoginSessions oidcLoginSessions;

        if (config.getBoolean(KEY_OIDC_SESSION_IN_DB)) {
            oidcLoginSessions = new OidcLoginSessionsInDb(config, hibernate);
        } else {
            oidcLoginSessions = new OidcLoginSessionsInMemory(config);
        }

        OidcResource oidcResource = new OidcResource(new OidcProvidersImpl(authTokens, userTable, config),
                oidcLoginSessions, serviceLocator);

        // new ChipsterOidcLoginSessionsInMemory(config));
        oidcResource.init(authTokens, userTable, config);
        AuthUserResource userResource = new AuthUserResource(userTable);
        AuthenticationRequestFilter authRequestFilter = new AuthenticationRequestFilter(hibernate, config, userTable,
                authTokens, jaasAuthProvider);

        // log also client errors in auth
        final ResourceConfig rc = RestUtils.getDefaultResourceConfig(serviceLocator, true)
                .register(tokenResource)
                .register(oidcResource)
                .register(userResource)
                .register(new HibernateRequestFilter(hibernate))
                .register(new HibernateResponseFilter(hibernate))
                // .register(new LoggingFilter())
                .register(authRequestFilter);

        JerseyStatisticsSource jerseyStatisticsSource = RestUtils.createJerseyStatisticsSource(rc);

        AuthAdminResource authAdminResource = new AuthAdminResource(hibernate, hibernateClasses, jerseyStatisticsSource,
                userTable, this.config);

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
        URI baseUri = URI.create(this.config.getBindUrl(Role.AUTH));
        this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc, false);
        RestUtils.configureGrizzlyThreads(httpServer, Role.AUTH, false, config);
        RestUtils.configureGrizzlyRequestLog(this.httpServer, Role.AUTH, LogType.API);

        jerseyStatisticsSource.collectConnectionStatistics(httpServer);

        this.httpServer.start();

        /*
         * Authenticate admin API using the same Rest API that all other admin APIs are
         * using
         * even if it is running in this same process. We have to set the address
         * explicitly, because
         * ServiceLocator isn't running yet.
         */

        URL bindUrl = URI.create(config.getBindUrl(Role.AUTH)).toURL();

        String localhostUrl = new URI(bindUrl.getProtocol(), null, "localhost", bindUrl.getPort(), bindUrl.getFile(),
                null, null).toString();

        this.adminAuthClient = new AuthenticationClient(localhostUrl, Role.AUTH,
                config.getPassword(Role.AUTH), Role.SERVER);
        this.adminServer = RestUtils.startAdminServer(
                authAdminResource, hibernate,
                Role.AUTH, config, adminAuthClient, serviceLocator);
    }

    /**
     * Refuse to start if any service account or the monitoring account has a blank
     * password or still has its default password
     *
     * The default passwords are public, so a deployment must not use them. They
     * can be allowed in a development environment with a configuration flag, blank
     * passwords never.
     *
     * Call this before starting anything that creates threads, otherwise the
     * process doesn't exit after the exception.
     * 
     * Package-private for the tests.
     */
    void checkDefaultPasswords() {
        refuseBlankAndDefaultPasswords("configuration", "keys", config.getBlankPasswordKeys(),
                config.getDefaultPasswordKeys());
    }

    /**
     * Refuse to start if any account in the users files of the JAAS configuration
     * has a blank password or one of the public passwords of the security/users
     * file in the repository
     *
     * The same rules as in checkDefaultPasswords(): the flag allows the default
     * passwords in a development environment, blank passwords never. An
     * expiration date that SimpleFileLoginModule can't parse is only logged,
     * because that account can't log in, but the others can, and the earlier
     * lenient date parser may have accepted it.
     *
     * A missing file is only logged, because a deployment that authenticates only
     * with OIDC doesn't need one, and SimpleFileLoginModule fails those logins
     * anyway. A file that exists but can't be read is an error, because then
     * there is no way to tell whether it has default passwords.
     *
     * Call this before starting anything that creates threads, see
     * checkDefaultPasswords().
     *
     * Package-private for the tests.
     *
     * The problems of all files are collected before refusing, so that the
     * operator sees everything at once instead of one file per restart.
     *
     * @param usersFiles passwdFile of each SimpleFileLoginModule, see
     *                   JaasAuthenticationProvider.getPasswordFiles()
     * @throws IOException if a users file can't be read
     */
    void checkUsersFiles(List<File> usersFiles) throws IOException {

        // "file: user1, user2" for each file that has such users
        List<String> blank = new ArrayList<>();
        List<String> defaults = new ArrayList<>();

        for (File usersFile : usersFiles) {

            if (!usersFile.exists()) {
                logger.warn("users file " + usersFile.getAbsolutePath() + " not found, logins against it will fail");
                continue;
            }

            List<UsersFile.Account> accounts = UsersFile.read(usersFile);

            List<String> invalidExpirations = UsersFile.getInvalidExpirationUsernames(accounts);

            if (!invalidExpirations.isEmpty()) {
                logger.warn("invalid expiration dates in " + usersFile + ": " + String.join(", ", invalidExpirations)
                        + ". These users can't log in, use the format yyyy-MM-dd");
            }

            List<String> blankUsers = UsersFile.getBlankPasswordUsernames(accounts);
            if (!blankUsers.isEmpty()) {
                blank.add(usersFile + ": " + String.join(", ", blankUsers));
            }

            List<String> defaultUsers = UsersFile.getDefaultPasswordUsernames(accounts);
            if (!defaultUsers.isEmpty()) {
                defaults.add(usersFile + ": " + String.join(", ", defaultUsers));
            }
        }

        refuseBlankAndDefaultPasswords("users files", "users", blank, defaults);
    }

    /**
     * Refuse blank passwords always and default passwords unless the flag allows
     * them
     *
     * @param location "configuration" or "users files", for the messages
     * @param items    "keys" or "users", what the lists contain
     * @param blank    configuration keys, or "file: users" entries, with a blank
     *                 password
     * @param defaults configuration keys, or "file: users" entries, with a default
     *                 password
     */
    private void refuseBlankAndDefaultPasswords(String location, String items, List<String> blank,
            List<String> defaults) {

        if (!blank.isEmpty()) {
            throw new IllegalStateException("blank passwords in " + location + ": " + String.join("; ", blank)
                    + ". Set new passwords for these " + items);
        }

        if (defaults.isEmpty()) {
            return;
        }

        if (config.getBoolean(KEY_ALLOW_DEFAULT_PASSWORDS)) {
            logger.warn("default passwords in " + location + " allowed by " + KEY_ALLOW_DEFAULT_PASSWORDS + ": "
                    + String.join("; ", defaults));
            return;
        }

        throw new IllegalStateException("default passwords in " + location + ": " + String.join("; ", defaults)
                + ". Set new passwords for these " + items + ", or set " + KEY_ALLOW_DEFAULT_PASSWORDS
                + ": true in a development environment");
    }

    /**
     * Main method.
     *
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
        adminAuthClient.close();
    }
}
