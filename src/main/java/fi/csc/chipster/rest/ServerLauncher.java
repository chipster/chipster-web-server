package fi.csc.chipster.rest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationService;
import fi.csc.chipster.backup.Backup;
import fi.csc.chipster.comp.RestCompServer;
import fi.csc.chipster.filebroker.FileBroker;
import fi.csc.chipster.filestorage.FileStorage;
import fi.csc.chipster.jobhistory.JobHistoryService;
import fi.csc.chipster.scheduler.Scheduler;
import fi.csc.chipster.servicelocator.ServiceLocator;
import fi.csc.chipster.sessiondb.SessionDb;
import fi.csc.chipster.sessionworker.SessionWorker;
import fi.csc.chipster.toolbox.ToolboxService;
import fi.csc.chipster.web.WebServer;

public class ServerLauncher {

	// this must not be static, otherwise logging configuration fails
	private final Logger logger = LogManager.getLogger();

	private final AuthenticationService auth;
	private final ServiceLocator serviceLocator;
	private final SessionDb sessionDb;
	private final Scheduler scheduler;
	private final ToolboxService toolbox;
	private final FileBroker fileBroker;
	private final WebServer web;

	private RestCompServer comp;

	private SessionDb sessionDbSlave;

	private final SessionWorker sessionWorker;
	private final JavascriptService typeService;

	private final JobHistoryService jobHistoryService;

	private final Backup backup;

	private final FileStorage fileStorage;

	public ServerLauncher(Config config, boolean verbose) throws Exception {

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


//		if (verbose) {
//			logger.info("starting comp");
//		}
//		comp = new RestCompServer(null, config);
//		comp.startServer();

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
			logger.info("press enter to stop");
		}

		System.in.read();

		stop();
		System.exit(0);
	}

	public final void stop() {

		if (backup != null) {
			try {
				backup.close();
			} catch (Exception e) {
				logger.warn("closing backup service failed", e);
			}
		}
		
		if (web != null) {
			try {
				web.close();
			} catch (Exception e) {
				logger.warn("closing web server failed", e);
			}
		}

		if (jobHistoryService != null) {
			try {
				jobHistoryService.close();
			} catch (Exception e) {
				logger.warn("closing job history service failed");
			}
		}

		if (typeService != null) {
			try {
				typeService.close();
			} catch (Exception e) {
				logger.warn("closing type service failed");
			}
		}

		if (comp != null) {
			try {
				comp.shutdown();
			} catch (Exception e) {
				logger.warn("closing toolbox failed", e);
			}
		}

		if (scheduler != null) {
			try {
				scheduler.close();
			} catch (Exception e) {
				logger.warn("closing scheduler failed", e);
			}
		}
		if (toolbox != null) {
			try {
				toolbox.close();
			} catch (Exception e) {
				logger.warn("closing toolbox failed", e);
			}
		}
		if (sessionWorker != null) {
			try {
				sessionWorker.close();
			} catch (Exception e) {
				logger.warn("closing session-worker failed");
			}
		}
		if (fileBroker != null) {
			try {
				fileBroker.close();
			} catch (Exception e) {
				logger.warn("closing file-broker failed", e);
			}
		}
		if (fileStorage != null) {
			try {
				fileStorage.close();
			} catch (Exception e) {
				logger.warn("closing file-storage failed", e);
			}
		}
		if (sessionDbSlave != null) {
			try {
				sessionDbSlave.close();
			} catch (Exception e) {
				logger.warn("closing session-db slave failed", e);
			}
		}
		if (sessionDb != null) {
			try {
				sessionDb.close();
			} catch (Exception e) {
				logger.warn("closing session-db failed", e);
			}
		}
		if (serviceLocator != null) {
			try {
				serviceLocator.close();
			} catch (Exception e) {
				logger.warn("closing service-locator failed", e);
			}
		}
		if (auth != null) {
			try {
				auth.close();
			} catch (Exception e) {
				logger.warn("closing auth failed", e);
			}
		}		
	}

	public static void main(String[] args) throws Exception {
		Config config = new Config();
		new ServerLauncher(config, true);
	}

	public SessionDb getSessionDb() {
		return sessionDb;
	}
}
