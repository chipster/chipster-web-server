package fi.csc.chipster.rest;

import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationService;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.backup.Backup;
import fi.csc.chipster.comp.RestCompServer;
import fi.csc.chipster.filebroker.FileBroker;
import fi.csc.chipster.filestorage.FileStorage;
import fi.csc.chipster.jobhistory.JobHistoryService;
import fi.csc.chipster.s3storage.S3Storage;
import fi.csc.chipster.scheduler.Scheduler;
import fi.csc.chipster.servicelocator.ServiceLocator;
import fi.csc.chipster.sessiondb.SessionDb;
import fi.csc.chipster.sessionworker.SessionWorker;
import fi.csc.chipster.toolbox.ToolboxService;
import fi.csc.chipster.web.WebServer;

public class ServerLauncher {

	// this must not be static, otherwise logging configuration fails
	private final Logger logger = LogManager.getLogger();

	private AuthenticationService auth = null;
	private ServiceLocator serviceLocator = null;
	private SessionDb sessionDb = null;
	private Scheduler scheduler = null;
	private ToolboxService toolbox = null;
	private FileBroker fileBroker = null;
	private WebServer web = null;

	private RestCompServer comp = null;

	private SessionDb sessionDbSlave = null;

	private SessionWorker sessionWorker = null;
	private JavascriptService typeService = null;

	private JobHistoryService jobHistoryService = null;

	private Backup backup = null;

	private FileStorage fileStorage = null;

	private S3Storage s3Storage = null;

	public ServerLauncher(Config config, boolean verbose) throws Exception {

		/*
		 * Configure TLS version
		 * 
		 * This is used only in S3StorageClient, but it's too late to configure it there
		 * when ServerLauncher is used. This is also a global settings, so we cannot
		 * limit its effect only to file-broker
		 */
		ChipsterS3Client.configureTLSVersion(config, null);

		long t = System.currentTimeMillis();

		if (verbose) {
			logger.info("starting authentication-service");
		}
		auth = new AuthenticationService(config);
		auth.startServer();

		if (verbose) {
			logger.info("starting service-locator");
		}
		serviceLocator = new ServiceLocator(config);
		serviceLocator.startServer();

		if (verbose) {
			logger.info("starting session-db");
		}
		sessionDb = new SessionDb(config);
		sessionDb.startServer();

		if (verbose) {
			logger.info("starting file-storage");
		}
		fileStorage = new FileStorage(config);
		fileStorage.startServer();

		if (verbose) {
			logger.info("starting s3-storage");
		}
		s3Storage = new S3Storage(config);
		s3Storage.startServer();

		if (verbose) {
			logger.info("starting file-broker");
		}
		fileBroker = new FileBroker(config);
		fileBroker.startServer();

		if (verbose) {
			logger.info("starting session-worker");
		}
		sessionWorker = new SessionWorker(config);
		sessionWorker.startServer();

		if (verbose) {
			logger.info("starting toolbox");
		}
		toolbox = new ToolboxService(config);
		toolbox.startServer();

		if (verbose) {
			logger.info("starting scheduler");
		}
		scheduler = new Scheduler(config);
		scheduler.startServer();

		// if (verbose) {
		// logger.info("starting comp");
		// }
		// comp = new RestCompServer(null, config);
		// comp.startServer();

		if (verbose) {
			logger.info("starting web server");
		}
		web = new WebServer(config);
		web.start();

		if (verbose) {
			logger.info("starting job history service");
		}
		jobHistoryService = new JobHistoryService(config);
		jobHistoryService.startServer();

		if (verbose) {
			logger.info("starting type service");
		}
		typeService = new JavascriptService("js/type-service");
		typeService.startServer();

		if (verbose) {
			logger.info("starting backup service");
		}
		backup = new Backup(config);
		backup.start();

		if (verbose) {
			logger.info("up and running ("
					+ ((System.currentTimeMillis() - t) / 1000) + " seconds)");
			logger.info("---------------------------");
			logger.info("press Ctrl + C to stop");

			addShutdownHook(this);

			// any way of keeping this thread alive would do, but Jetty happens to have the
			// nice method join() for this
			sessionWorker.getHttpServer().join();
		}
	}

	private void addShutdownHook(ServerLauncher serverLauncher) {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {

				serverLauncher.stop();
			}
		});
	}

	public void stopComponents(HashMap<String, ServerComponent> components) {
		for (String role : components.keySet()) {

			ServerComponent component = components.get(role);

			if (component != null) {
				new Thread(new Runnable() {

					@Override
					public void run() {
						try {
							logger.debug("close " + role);
							component.close();
						} catch (Exception e) {
							logger.warn("closing " + role + " failed", e);
						}
					}
				}).start();
			}
		}
	}

	public final void stop() {

		HashMap<String, ServerComponent> components = new HashMap<String, ServerComponent>();

		/*
		 * Something breaks WebSocket connections soon after Ctrl+C is pressed and I
		 * couldn't figure out what.
		 * 
		 * Stop components with websocket connection first to avoid ugly stack traces.
		 */
		components.put(Role.FILE_STORAGE, fileStorage);
		components.put(Role.S3_STORAGE, s3Storage);
		components.put(Role.SCHEDULER, scheduler);

		stopComponents(components);
		components.clear();

		components.put(Role.BACKUP, backup);
		components.put(Role.WEB_SERVER, web);
		components.put(Role.JOB_HISTORY, jobHistoryService);
		components.put(Role.TYPE_SERVICE, typeService);
		components.put(Role.COMP, comp);
		components.put(Role.TOOLBOX, toolbox);
		components.put(Role.SESSION_WORKER, sessionWorker);
		components.put(Role.FILE_BROKER, fileBroker);
		components.put("session-db-slave", sessionDbSlave);
		components.put(Role.SESSION_DB, sessionDb);
		components.put(Role.SERVICE_LOCATOR, serviceLocator);
		components.put(Role.AUTH, auth);

		stopComponents(components);
	}

	public static void main(String[] args) throws Exception {
		Config config = new Config();

		new ServerLauncher(config, true);
	}

	public SessionDb getSessionDb() {
		return sessionDb;
	}
}
