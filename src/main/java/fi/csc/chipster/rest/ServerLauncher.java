package fi.csc.chipster.rest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationService;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.servicelocator.ServiceLocator;
import fi.csc.chipster.sessiondb.SessionDb;

public class ServerLauncher {
	
	// this must not be static, otherwise logging configuration fails
	private final Logger logger = LogManager.getLogger();

	private AuthenticationService auth;
	private ServiceLocator serviceLocator;
	private SessionDb sessionDb;
	
	public ServerLauncher(Config config, String role, boolean verbose) {
		if (verbose) {
			logger.info("starting authentication-service");
		}
		
		auth = new AuthenticationService(config);
		auth.startServer();
		
		if (!Role.AUTHENTICATION_SERVICE.equals(role)) {
			if (verbose) {
				logger.info("starting service-locator");
			}
			
			serviceLocator = new ServiceLocator(config);
			serviceLocator.startServer();
			
			if (!Role.SERVICE_LOCATOR.equals(role)) {
				if (verbose) {
					logger.info("starting session-db");
				}
				
				sessionDb = new SessionDb(config);
				sessionDb.startServer();
			}
		}
		if (verbose) {
			logger.info("up and running");
		}
	}		

	public void stop() {
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
		
	public static void main(String[] args) {
		Config config = new Config();
		new ServerLauncher(config, Role.SESSION_DB, true);
	}
}