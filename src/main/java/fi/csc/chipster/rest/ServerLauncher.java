package fi.csc.chipster.rest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationService;
import fi.csc.chipster.comp.RestCompServer;
import fi.csc.chipster.filebroker.FileBroker;
import fi.csc.chipster.proxy.ChipsterProxyServer;
import fi.csc.chipster.scheduler.Scheduler;
import fi.csc.chipster.servicelocator.ServiceLocator;
import fi.csc.chipster.sessionWorker.SessionWorker;
import fi.csc.chipster.sessiondb.SessionDb;
import fi.csc.chipster.toolbox.ToolboxService;
import fi.csc.chipster.web.WebServer;

public class ServerLauncher {
	
	// this must not be static, otherwise logging configuration fails
	private final Logger logger = LogManager.getLogger();

	private AuthenticationService auth;
	private ServiceLocator serviceLocator;
	private SessionDb sessionDb;
	private Scheduler scheduler;
	private ToolboxService toolbox;
	private ChipsterProxyServer proxy;
	private FileBroker fileBroker;
	private WebServer web;

	private RestCompServer comp;

	private SessionDb sessionDbSlave;

	private SessionWorker sessionWorker;
	
	
	public ServerLauncher(Config config, boolean verbose) throws Exception {
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
		
//		if (verbose) {
//			logger.info("starting session-db slave");
//		}		
//		Config slaveConfig = new Config();
//		slaveConfig.set("session-db-replicate", "true");
//		slaveConfig.set("session-db-bind", "http://127.0.0.1:8070/sessiondb/");
//		slaveConfig.set("session-db-events-bind", "http://127.0.0.1:8074/sessiondbevents/");
//		slaveConfig.set("session-db-name", "session-db-replicate");
//		sessionDbSlave = new SessionDb(slaveConfig);
//		sessionDbSlave.startServer();
		
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
			logger.info("starting scheduler");
		}		
		scheduler = new Scheduler(config);
		scheduler.startServer();

		if (verbose) {
			logger.info("starting toolbox");
		}		
		toolbox = new ToolboxService(config);
		toolbox.startServer();

		if (verbose) {
			logger.info("starting comp");
		}		
		comp = new RestCompServer(null);
		
		if (verbose) {
			logger.info("starting web server");
		}
		web = new WebServer(config);
		web.start();
		
		if (verbose) {
			logger.info("starting proxy");
		}		
		proxy = new ChipsterProxyServer(config);
		proxy.startServer();
		
		if (verbose) {
			logger.info("up and running");
		}
	}		

	public void stop() {
		
		if (proxy != null) {
			try {
				proxy.close();
			} catch (Exception e) {
				logger.warn("closing proxy failed", e);
			}
		}
		
		if (web != null) {
			try {
				web.close();
			} catch (Exception e) {
				logger.warn("closing web server failed", e);
			}
		}
		
		if (comp != null) {
			try {
				comp.shutdown();
			} catch (Exception e) {
				logger.warn("closing toolbox failed", e);
			}
		}
		
		if (toolbox != null) {
			try {
				toolbox.close();
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
				logger.warn("closing filebroker failed", e);
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
