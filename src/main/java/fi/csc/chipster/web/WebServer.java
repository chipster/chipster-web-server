package fi.csc.chipster.web;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.rewrite.handler.HeaderPatternRule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
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

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setDirAllowed(false);
        resourceHandler.setWelcomeFiles(new String[] { INDEX_HTML });

        // let the app handle pushState URLs
        server.setErrorHandler(new PushStateErrorHandler(Path.of(rootPath).resolve(INDEX_HTML)));
        // ContextHandler is needed only to set the base resource as path
        ContextHandler contextHandler = new ContextHandler("/");
        contextHandler.setBaseResourceAsPath(root.toPath());
        contextHandler.setHandler(resourceHandler);

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
        cacheRule.setPattern("/*");
        cacheRule.setHeaderName("Cache-Control");
        cacheRule.setHeaderValue("no-store");
        cacheRewrite.addRule(cacheRule);

        /*
         * The contexHandler serving the resources must be inside
         * cacheRewrite in the handler tree for both to have an effect.
         * Having them both in handler collection doesn't work, because
         * the request is considered handled after first handle() method
         * returns true.
         */
        cacheRewrite.setHandler(contextHandler);

        ContextHandlerCollection handlers = new ContextHandlerCollection();
        handlers.addHandler(cacheRewrite);
        handlers.addHandler(new DefaultHandler());
        server.setHandler(handlers);

        server.setDumpAfterStart(true);

        StatusSource stats = RestUtils.createStatisticsListener(server);

        server.start();

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
