package fi.csc.chipster.web;

import java.io.File;
import java.net.URI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.rewrite.handler.HeaderPatternRule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

public class WebServer {

    private static final String INDEX_HTML = "index.html";

    private static final Logger logger = LogManager.getLogger();

    private Config config;
    private Server server;

    private ServiceLocatorClient serviceLocator;

    private AuthenticationClient authService;

    private HttpServer adminServer;

    public WebServer(Config config) {
        this.config = config;
    }

    public void start() throws Exception {

        String username = Role.WEB_SERVER;
        String password = config.getPassword(username);

        this.serviceLocator = new ServiceLocatorClient(config);
        this.authService = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
        this.serviceLocator.setCredentials(authService.getCredentials());

        URI baseUri = URI.create(config.getBindUrl(Role.WEB_SERVER));

        server = new Server();
        RestUtils.configureJettyThreads(server, Role.WEB_SERVER, config);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(baseUri.getPort());
        connector.setHost(baseUri.getHost());
        server.addConnector(connector);

        String rootPath = config.getString(Config.KEY_WEB_SERVER_WEB_ROOT_PATH);

        File root = new File(rootPath);
        logger.info("web root: " + root.getCanonicalPath());

        if (!root.exists()) {
            throw new IllegalArgumentException("web root " + rootPath + " doesn't exist");
        }

        // let the app handle pushState URLs
        ForwardingResourceHandler forwardingResourceHandler = new ForwardingResourceHandler();
        forwardingResourceHandler.setDirectoriesListed(false);
        forwardingResourceHandler.setWelcomeFiles(new String[] { INDEX_HTML });

        // some ContextHandler is needed to enable symlinks
        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setResourceBase(rootPath);
        contextHandler.setHandler(forwardingResourceHandler);
        // TODO how should SymlinkAllowedResourceAliasChecker be used? At least this
        // seems to work...
        contextHandler.addAliasCheck(new SymlinkAllowedResourceAliasChecker(contextHandler));

        // generate a better error message if the ErrorHandler page is missing instead
        // of the
        // vague NullPointerException
        File indexFile = new File(root, INDEX_HTML);
        if (!indexFile.exists()) {
            logger.warn("index.html " + indexFile + " doesn't exist");
        }

        // disable caching of the front page, because we use it for maintenance break
        // communication
        RewriteHandler cacheRewrite = new RewriteHandler();
        HeaderPatternRule cacheRule = new HeaderPatternRule();
        cacheRule.setPattern("*.html");
        cacheRule.setName("Cache-Control");
        cacheRule.setValue("no-store");
        cacheRewrite.addRule(cacheRule);

        HandlerList handlers = new HandlerList();
        handlers.addHandler(cacheRewrite);
        handlers.addHandler(contextHandler);
        handlers.addHandler(new DefaultHandler());
        server.setHandler(handlers);

        StatusSource stats = RestUtils.createStatisticsListener(server);

        // Start things up! By using the server.join() the server thread will join with
        // the current thread.
        // See
        // "http://docs.oracle.com/javase/1.5.0/docs/api/java/lang/Thread.html#join()"
        // for more details.
        server.start();
        // server.join();

        adminServer = RestUtils.startAdminServer(Role.WEB_SERVER, config, authService, this.serviceLocator, stats);
    }

    public static void main(String[] args) throws Exception {
        WebServer service = new WebServer(new Config());

        RestUtils.shutdownGracefullyOnInterrupt(service.server, Role.WEB_SERVER);

        service.start();
    }

    public void close() {
        RestUtils.shutdown("web-server-admin", adminServer);
        try {
            server.stop();
        } catch (Exception e) {
            logger.warn("failed to stop the web server", e);
        }
    }
}
