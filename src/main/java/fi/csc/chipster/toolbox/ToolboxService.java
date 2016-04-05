package fi.csc.chipster.toolbox;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.toolbox.resource.ModuleResource;
import fi.csc.chipster.toolbox.resource.ToolResource;

/**
 * Toolbox rest service.
 *
 */
public class ToolboxService {

	private static final String MODULES_DIR_NAME = "modules";
	private static final String MODULES_ZIP_NAME = "modules.zip";

	private static final String[] MODULES_SEARCH_LOCATIONS = { ".", "../chipster-tools",
			"../chipster-tools/build/distributions" };

	private Logger logger = LogManager.getLogger();

	private Config config;
	private Toolbox toolbox;
	private String url;
	private HttpServer httpServer;

	@SuppressWarnings("unused")
	private String serviceId;

	public ToolboxService(Config config) throws IOException, URISyntaxException {
		this.config = config;
		this.url = config.getString(Config.KEY_TOOLBOX_BIND_URL);
		loadToolbox();
	}

	/**
	 * Used by ToolboxServer
	 * 
	 * @param url
	 * @throws IOException
	 * @throws URISyntaxException
	 */
	public ToolboxService(String url) throws IOException, URISyntaxException {
		this.url = url;
		loadToolbox();
	}

	private void loadToolbox() throws IOException, URISyntaxException {

		Path foundPath = findModulesDir();
		
		if (Files.isDirectory(foundPath)) {
			this.toolbox = new Toolbox(foundPath);

			Path tempDir = Files.createTempDirectory(MODULES_DIR_NAME);
			Path tempZipFile = tempDir.resolve(MODULES_ZIP_NAME);
			
			dirToZip(foundPath, tempZipFile);
			byte [] zipContents = Files.readAllBytes(tempZipFile);
			this.toolbox.setZipContents(zipContents);
			Files.delete(tempZipFile);
			Files.delete(tempDir);
		}
		
		// found modules zip
		else {
			FileSystem fs = FileSystems.newFileSystem(foundPath, null);
			Path modulesPath = fs.getPath(MODULES_DIR_NAME);
			this.toolbox = new Toolbox(modulesPath);

			byte [] zipContents = Files.readAllBytes(foundPath);
			this.toolbox.setZipContents(zipContents);
		}
		
	}

	/**
	 * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
	 * application.
	 * 
	 * @return Grizzly HTTP server.
	 * @throws URISyntaxException
	 */
	public void startServer() throws IOException, URISyntaxException {

		final ResourceConfig rc = RestUtils.getDefaultResourceConfig().register(new ToolResource(this.toolbox))
				.register(new ModuleResource(this.toolbox));
				// .register(new LoggingFilter())

		// create and start a new instance of grizzly http server
		// exposing the Jersey application at BASE_URI
		URI baseUri = URI.create(this.url);
		this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
		logger.info("toolbox service running at " + baseUri);
		
		// try to register this toolbox to the service locator
		// fails if service locator not up, toolbox service will still be
		// functional though
		if (this.config != null) {
			try {
				registerToServiceLocator(config);
			} catch (Exception e) {
				logger.info("register to service locator failed");
			}
		}
	}

	/**
	 * Main method.
	 * 
	 * @throws URISyntaxException
	 */
	public static void main(String[] args) throws IOException, URISyntaxException {

		final ToolboxService server = new ToolboxService(new Config());
		server.startServer();
		RestUtils.waitForShutdown("toolbox", server.getHttpServer());
	}

	private HttpServer getHttpServer() {
		return this.httpServer;
	}

	public void close() {
		RestUtils.shutdown("toolbox", httpServer);
	}

	private void registerToServiceLocator(Config config) {
		String username = config.getString(Config.KEY_TOOLBOX_USERNAME);
		String password = config.getString(Config.KEY_TOOLBOX_PASSWORD);

		ServiceLocatorClient locatorClient = new ServiceLocatorClient(config);
		AuthenticationClient authClient = new AuthenticationClient(locatorClient, username, password);

		this.serviceId = locatorClient.register(Role.TOOLBOX, authClient, config.getString(Config.KEY_TOOLBOX_URL));
	}

	/**
	 * Try to locate modules dir or modules zip
	 * 
	 * @return path to modules dir or modules zip
	 * @throws FileNotFoundException
	 *             if modules dir or zip not found
	 */
	private Path findModulesDir() throws FileNotFoundException {

		for (String location : MODULES_SEARCH_LOCATIONS) {

			Path path;

			// search modules dir
			path = Paths.get(location, MODULES_DIR_NAME);
			logger.info("looking for " + path);
			if (Files.isDirectory(path)) {
				logger.info("modules directory " + path + " found");
				return path;
			}

			// search modules zip
			else {
				path = Paths.get(location, MODULES_ZIP_NAME);
				logger.info("looking for " + path);
				if (Files.exists(path)) {
					logger.info("modules zip " + path + " found");
					return path;
				}
			}
		}

		logger.warn("modules not found");
		throw new FileNotFoundException("modules not found");

	}
	
	public void dirToZip(Path srcDir, Path destZip) throws IOException, URISyntaxException {
		
		logger.debug("packaging " + srcDir + " -> " + destZip);
		
		if (Files.exists(destZip)) {
			logger.info("deleting existing " + destZip);
			Files.delete(destZip);
		}
		
		// destZip.toUri() does not work
		URI zipLocation = new URI("jar:file:" + destZip);
		
		// create zip
		Map<String, String> env = new HashMap<String, String>();
		env.put("create", "true");
		FileSystem zipFs = FileSystems.newFileSystem(zipLocation, env);
		
		// copy recursively
		Files.walkFileTree(srcDir, new SimpleFileVisitor<Path>() {
			public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) throws IOException {
				return copy(file);
			}
			public FileVisitResult preVisitDirectory( Path dir, BasicFileAttributes attrs ) throws IOException {
				return copy(dir);
			}
			private FileVisitResult copy(Path src) throws IOException {
				Path dest = src.subpath(srcDir.getNameCount() - 1, src.getNameCount());
				Path destInZip = zipFs.getPath(dest.toString());
				
				// permissions are not necessarily correct though, so they are also reset in comp
				Files.copy(src, destInZip, new CopyOption[] { StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING });
				return FileVisitResult.CONTINUE;	
			}
		});

		zipFs.close();			
	}


}
