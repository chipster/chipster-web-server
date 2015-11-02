package fi.csc.chipster.rest;

import javax.servlet.ServletException;
import javax.websocket.DeploymentException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationService;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.proxy.ProxyServer;
import fi.csc.chipster.scheduler.Scheduler;
import fi.csc.chipster.servicelocator.ServiceLocator;
import fi.csc.chipster.sessiondb.SessionDb;

public class ServerLauncher {
	
	// this must not be static, otherwise logging configuration fails
	private final Logger logger = LogManager.getLogger();

	private AuthenticationService auth;
	private ServiceLocator serviceLocator;
	private SessionDb sessionDb;

	private Scheduler scheduler;

	private ProxyServer proxy;
	
	public ServerLauncher(Config config, String role, boolean verbose) throws ServletException, DeploymentException, InterruptedException {
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
			logger.info("starting scheduler");
		}		
		scheduler = new Scheduler(config);
		scheduler.startServer();
		
		if (verbose) {
			logger.info("starting proxy");
		}		
		proxy = new ProxyServer(config);
		proxy.startServer();
		
		if (verbose) {
			logger.info("up and running");
		}
	}		

	public void stop() {
		
		if (proxy != null) {
			proxy.close();
		}
		if (scheduler != null) {
			scheduler.close();			
		}			
		if (sessionDb != null) {
			sessionDb.close();
		}
		if (serviceLocator != null) {
			serviceLocator.close();
		}
		if (auth != null) {
			auth.close();			
		}
	}	
		
	public static void main(String[] args) throws ServletException, DeploymentException, InterruptedException {
		Config config = new Config();
		new ServerLauncher(config, Role.SESSION_DB, true);
	}

	public SessionDb getSessionDb() {
		return sessionDb;
	}
}
