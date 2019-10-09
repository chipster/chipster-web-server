package fi.csc.chipster.scheduler;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.websocket.DeploymentException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;

public class Scheduler {

	private Logger logger = LogManager.getLogger();
	
	@SuppressWarnings("unused")
	private String serviceId;	
	private Config config;

	private AuthenticationClient authService;
	private ServiceLocatorClient serviceLocator;
	private SessionDbClient sessionDbClient;
	private HttpServer adminServer;

	private JobScheduler jobScheduler;

	private WorkflowScheduler workflowScheduler;
	
	public Scheduler(Config config) {
		this.config = config;
	}

    public void startServer() throws ServletException, DeploymentException, InterruptedException, RestException, IOException {
    	
    	String username = Role.SCHEDULER;
    	String password = config.getPassword(username);
    	    	
		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());

    	this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.SERVER);    	
    	    	    
    	this.jobScheduler = new JobScheduler(serviceLocator, authService, sessionDbClient, config);
    	this.workflowScheduler = new WorkflowScheduler(serviceLocator, authService, sessionDbClient, config);
    	
    	logger.info("starting the admin rest server");
    	
    	this.adminServer = RestUtils.startAdminServer(
    			new AdminResource(this.jobScheduler, this.jobScheduler.getPubSubServer(), this.workflowScheduler), null, Role.SCHEDULER, config, authService, this.serviceLocator);
    	    	
    	logger.info("scheduler is up and running");    		
    }    

    /**
     * Main method.
     * @param args
     * @throws Exception 
     */
    public static void main(String[] args) throws Exception {
    	        
        Scheduler server = new Scheduler(new Config());
    	try {
    		server.startServer();
    	} catch (Exception e) {
    		System.err.println("scheduler startup failed, exiting");
    		e.printStackTrace(System.err);
    		server.close();
    		System.exit(1);
    	}
    }

	public void close() {
		
		this.jobScheduler.close();
		this.workflowScheduler.close();

		RestUtils.shutdown("scheduler-admin", adminServer);
		
		try {
			sessionDbClient.close();
		} catch (IOException e) {
			logger.warn("failed to stop the session-db client", e);
		}
	}
}
