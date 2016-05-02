package fi.csc.chipster.toolbox;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.toolbox.resource.ModuleResource;
import fi.csc.chipster.toolbox.resource.ToolResource;

/**
 * Toolbox rest service.
 *
 */
public class ToolboxService {

	private static final String TOOLS_DIR_NAME = "tools";
	public static final String TOOLS_ZIP_NAME = TOOLS_DIR_NAME + ".zip";
	private static final String[] TOOLS_SEARCH_LOCATIONS = { ".", "../chipster-tools",
			"../chipster-tools/build/distributions" };

	private static final String RELOAD_DIR = "reload";
	private static final String RELOAD_FILE = "touch-me-to-reload-tools";
	
	private Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private Config config;
	private Toolbox toolbox;
	private String url;
	private HttpServer httpServer;
	private WatchService reloadWatcher;

	private ToolResource toolResource;
	private ModuleResource moduleResource;

	public ToolboxService(Config config) throws IOException, URISyntaxException {
		this.config = config;
		this.url = config.getString(Config.KEY_TOOLBOX_BIND_URL);
		initialise();
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
		initialise();
	}

	private void initialise() throws IOException, URISyntaxException {

		// load toolbox
		Toolbox newToolbox = loadToolbox();
		if (newToolbox != null) {
			this.toolbox = newToolbox;
		} else {
			throw new RuntimeException("failed to load toolbox");
		}
		
		// start reload watch 
		startReloadWatch();
		
	}
	
	
	private Toolbox loadToolbox() throws IOException, URISyntaxException {

		Path foundPath = findModulesDir();
		
		Toolbox box;
		if (Files.isDirectory(foundPath)) {
			box = new Toolbox(foundPath);

			Path tempDir = Files.createTempDirectory(TOOLS_DIR_NAME);
			Path tempZipFile = tempDir.resolve(TOOLS_ZIP_NAME);
			
			dirToZip(foundPath, tempZipFile);
			byte [] zipContents = Files.readAllBytes(tempZipFile);
			box.setZipContents(zipContents);
			Files.delete(tempZipFile);
			Files.delete(tempDir);
		}
		
		// found modules zip
		else {
			FileSystem fs = FileSystems.newFileSystem(foundPath, null);
			Path modulesPath = fs.getPath(TOOLS_DIR_NAME);
			box = new Toolbox(modulesPath);

			byte [] zipContents = Files.readAllBytes(foundPath);
			box.setZipContents(zipContents);
		}

		return box;
	}

	private void reloadToolbox() {
		logger.info("reloading tools");
		Toolbox newToolbox;
		try {
			newToolbox = loadToolbox();
		
			if (newToolbox == null) {
				logger.warn("failed to reload tools");
				return;
			}		
			// switch to new toolbox
			this.toolbox = newToolbox;
			if (this.toolResource != null) { // null if rest server not started yet
				this.toolResource.setToolbox(newToolbox);
			}
			if (this.moduleResource != null) { // null if rest server not started yet
				this.moduleResource.setToolbox(newToolbox);
			}

		} catch (Exception e) {
			logger.warn("failed to reload tools");
			return;
		}

		logger.info("tools reload done");
		
	}
	
	
	/**
	 * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
	 * application.
	 * 
	 * @return Grizzly HTTP server.
	 * @throws URISyntaxException
	 */
	public void startServer() throws IOException, URISyntaxException {

		this.toolResource = new ToolResource(this.toolbox);
		this.moduleResource = new ModuleResource(toolbox);
		final ResourceConfig rc = RestUtils.getDefaultResourceConfig().register(this.toolResource)
				.register(moduleResource);
				// .register(new LoggingFilter())

		// create and start a new instance of grizzly http server
		// exposing the Jersey application at BASE_URI
		URI baseUri = URI.create(this.url);
		this.httpServer = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
		logger.info("toolbox service running at " + baseUri);
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
		closeReloadWatcher();
		RestUtils.shutdown("toolbox", httpServer);
	}

	private void closeReloadWatcher() {
		if (this.reloadWatcher != null) {
			try {
				reloadWatcher.close();
			} catch (IOException e) {
				logger.warn("failed to close reload watcher");
			}
		}
	}

	/**
	 * Try to locate tools dir or tools zip
	 * 
	 * @return path to tolls dir or tools zip
	 * @throws FileNotFoundException
	 *             if tools dir or zip not found
	 */
	private Path findModulesDir() throws FileNotFoundException {

		for (String location : TOOLS_SEARCH_LOCATIONS) {

			Path path;

			// search modules dir
			path = Paths.get(location, TOOLS_DIR_NAME);
			logger.info("looking for " + path);
			if (Files.isDirectory(path)) {
				logger.info("modules directory " + path + " found");
				return path;
			}

			// search modules zip
			else {
				path = Paths.get(location, TOOLS_ZIP_NAME);
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

	public Toolbox getToolbox() {
		return this.toolbox;
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

	
	private void startReloadWatch() {
		
		new Thread(new Runnable() {

			@Override
			public void run() {
				Path reloadDir = Paths.get(RELOAD_DIR);
				Path reloadFile = reloadDir.resolve(RELOAD_FILE);

				try {
					// create reload dir and file
					Files.createDirectories(reloadDir);
					try {
						Files.createFile(reloadFile);
					} catch (FileAlreadyExistsException e) {
						// ignore
					}
					
					// register watcher
					reloadWatcher = FileSystems.getDefault().newWatchService(); 
					reloadDir.register(reloadWatcher, ENTRY_CREATE, ENTRY_MODIFY);				
					logger.info("watching " + reloadFile + " for triggering tools reload");

					// watch
					while (true) {
						WatchKey key;
						try {
							key = reloadWatcher.take();
						} catch (InterruptedException | ClosedWatchServiceException e ) {
							break;
						}

						for (WatchEvent<?> event : key.pollEvents()) {
							WatchEvent.Kind<?> kind = event.kind();

							@SuppressWarnings("unchecked")
							WatchEvent<Path> ev = (WatchEvent<Path>) event;
							Path fileName = ev.context();

							if ((kind == ENTRY_MODIFY || kind == ENTRY_CREATE) &&
									fileName.toString().equals(RELOAD_FILE)) {
								logger.info("tool reload requested");
								reloadToolbox();
							}
						}

						boolean valid = key.reset();
						if (!valid) {
							break;
						}
					}
				} catch (Exception e) {
					logger.warn("got exception while watching reload dir " + reloadDir, e);
				} finally {
					closeReloadWatcher();
				}
				
				logger.info("stopped watching " + reloadDir);

			}
		}, "toolbox-reload-watch").start();
	}
	

}
