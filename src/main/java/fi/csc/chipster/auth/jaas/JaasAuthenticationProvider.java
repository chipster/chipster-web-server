package fi.csc.chipster.auth.jaas;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class JaasAuthenticationProvider implements AuthenticationProvider {

    private static final String LOGIN_CONTEXT_NAME = "Chipster"; // login context name in JAAS configuration file
    // the JDK uses this login context when the named one isn't configured
    private static final String LOGIN_CONTEXT_NAME_FALLBACK = "other";

    private static Logger logger = LogManager.getLogger();

    private String confPath;
    private Configuration configuration;

    /**
     * Read the JAAS configuration
     *
     * A missing or invalid configuration file fails here, i.e. on startup.
     * Earlier versions failed at the first login instead.
     *
     * @param confPath path or URL of the configuration file, see
     *                 java.security.auth.login.config
     * @throws IOException if the configuration file can't be read or parsed
     */
    public JaasAuthenticationProvider(String confPath) throws IOException {
        this.confPath = confPath;
        this.configuration = initialize(confPath);
    }

    /**
     * Find the users files of the SimpleFileLoginModules in the JAAS configuration
     *
     * @return the passwdFile of each SimpleFileLoginModule in the Chipster login
     *         context, or in the "other" context when Chipster isn't configured,
     *         like the LoginContext does, in the order of the configuration file
     */
    public List<File> getPasswordFiles() {

        AppConfigurationEntry[] entries = configuration.getAppConfigurationEntry(LOGIN_CONTEXT_NAME);
        if (entries == null) {
            entries = configuration.getAppConfigurationEntry(LOGIN_CONTEXT_NAME_FALLBACK);
        }

        if (entries == null) {
            logger.warn("login context " + LOGIN_CONTEXT_NAME + " or " + LOGIN_CONTEXT_NAME_FALLBACK
                    + " not found from the JAAS configuration " + confPath + ", all JAAS logins will fail");
            return List.of();
        }

        return Arrays.stream(entries)
                .filter(entry -> isSimpleFileLoginModule(entry.getLoginModuleName()))
                .map(entry -> getPasswordFile(entry))
                .collect(Collectors.toList());
    }

    /**
     * Whether the login module is a SimpleFileLoginModule or a subclass of it
     *
     * The class is loaded with the context class loader of the thread, like the
     * LoginContext loads the login modules, so that this sees the same classes. A
     * class that can't be found isn't one. Its logins fail anyway, when the
     * LoginContext can't load it either.
     */
    private static boolean isSimpleFileLoginModule(String className) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }
        try {
            Class<?> moduleClass = Class.forName(className, false, loader);
            return SimpleFileLoginModule.class.isAssignableFrom(moduleClass);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private File getPasswordFile(AppConfigurationEntry entry) {
        String path = (String) entry.getOptions().get(SimpleFileLoginModule.OPTION_PASSWD_FILE);
        if (path == null) {
            throw new IllegalStateException(SimpleFileLoginModule.class.getName() + " has no option "
                    + SimpleFileLoginModule.OPTION_PASSWD_FILE + " in the JAAS configuration " + confPath);
        }
        return new File(path);
    }

    public boolean authenticate(String username, char[] password) {

        // get the login context
        LoginContext lc = null;
        try {
            lc = new LoginContext(LOGIN_CONTEXT_NAME, null, new SimpleCallbackHandler(username, password),
                    configuration);
        } catch (LoginException le) {
            logger.error("Cannot create LoginContext. ", le);
            return false;
        } catch (SecurityException se) {
            logger.error("Cannot create LoginContext. ", se);
            return false;
        }

        // authenticate
        try {
            // attempt authentication
            lc.login();
            // if we return with no exception, authentication succeeded
            logger.info("Authentication successful for " + username);
        } catch (FailedLoginException fle) {
            logger.info("Authentication failed for " + username);
            return false;
        } catch (LoginException le) {
            logger.error("Could not perform authentication for " + username, le);
            return false;
        }

        // authentication ok;
        return true;
    }

    private class SimpleCallbackHandler implements CallbackHandler {

        private String username;
        private char[] password;

        public SimpleCallbackHandler(String username, char[] password) {
            this.username = username;
            this.password = password;
        }

        public void handle(Callback[] callbacks) throws IOException,
                UnsupportedCallbackException {

            for (int i = 0; i < callbacks.length; i++) {
                if (callbacks[i] instanceof NameCallback) {
                    NameCallback nc = (NameCallback) callbacks[i];
                    nc.setName(this.username);

                } else if (callbacks[i] instanceof PasswordCallback) {
                    PasswordCallback pc = (PasswordCallback) callbacks[i];
                    pc.setPassword(this.password);

                } else {
                    throw new UnsupportedCallbackException(callbacks[i], "Unrecognized Callback");
                }
            }
        }
    }

    /**
     * Set the configuration file for the JAAS classes that read the system
     * property, and parse it into a Configuration of our own
     *
     * The LoginContexts get this Configuration explicitly, so that they
     * authenticate against the same file that getPasswordFiles() checks on
     * startup, whatever the global Configuration.getConfiguration() holds (it is
     * loaded once per JVM, possibly before the property was set). The JDK
     * resolves the property the same way for both: ${property} expansion, URL or
     * file path.
     */
    private Configuration initialize(String confPath) throws IOException {

        // set location of the config
        System.setProperty("java.security.auth.login.config", confPath);

        try {
            return Configuration.getInstance("JavaLoginConfig", null);
        } catch (NoSuchAlgorithmException | SecurityException e) {
            // the JDK wraps the IOException of a missing or invalid file in these
            throw new IOException("failed to read the JAAS configuration " + confPath, e);
        }
    }
}
