package fi.csc.chipster.scheduler;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.servlet.ServletException;
import javax.websocket.DeploymentException;
import javax.websocket.MessageHandler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.rest.websocket.PubSubServer.TopicCheck;
import fi.csc.chipster.scheduler.JobCommand.Command;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.SessionDb;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.microarray.messaging.JobState;

public class Scheduler implements SessionEventListener, MessageHandler.Whole<String>, TopicCheck {
	
	private Logger logger = LogManager.getLogger();
	
	private AuthenticationClient authService;
	private Config config;

	private ServiceLocatorClient serviceLocator;

	@SuppressWarnings("unused")
	private String serviceId;

	private SessionDbClient sessionDbClient;

	private PubSubServer pubSubServer;
	
	ConcurrentLinkedQueue<UUID> scheduledJobs = new ConcurrentLinkedQueue<>();
	
	public Scheduler(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     * @throws DeploymentException 
     * @throws ServletException 
     * @throws InterruptedException 
     * @throws Exception 
     */
    public void startServer() throws ServletException, DeploymentException, InterruptedException {
    	
    	String username = "scheduler";
    	String password = "schedulerPassword";
    	    	
		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);
		this.serviceId = serviceLocator.register(Role.SCHEDULER, authService, config.getString("scheduler"));	      
    	
    	this.sessionDbClient = new SessionDbClient(serviceLocator, authService);
    	this.sessionDbClient.subscribe(SessionDb.JOBS_TOPIC, this);    	
    	
    	this.pubSubServer = new PubSubServer(config.getString("scheduler-bind"), "/events", authService, this, this, "scheduler-events");
    	this.pubSubServer.start();		
    }
    

	@Override
	public boolean isAuthorized(AuthPrincipal principal, String topic) {
		return principal.getRoles().contains(Role.SERVER);			
	}

    /**
     * Main method.
     * @param args
     * @throws Exception 
     */
    public static void main(String[] args) throws Exception {
    	
        final Scheduler server = new Scheduler(new Config());
        server.startServer();
    }

	public void close() {
		try {
			sessionDbClient.close();
		} catch (IOException e) {
			logger.warn("failed to stop the session-db client", e);
		}
		pubSubServer.stop();
	}

	@Override
	public void onEvent(SessionEvent e) {
		logger.debug("received a job event: " + e.getResourceType() + " " + e.getType());
		if (ResourceType.JOB == e.getResourceType()) {
			if (EventType.CREATE == e.getType() || EventType.UPDATE == e.getType()) {
				logger.debug("get job");
				Job job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
				logger.debug("handle event");
				handleSessionDbEvent(e.getSessionId(), job);
			}		
		}		
	}

	private void handleSessionDbEvent(UUID sessionId, Job job) {
		logger.debug("handling a job with state " + job.getState());
		if (JobState.NEW == job.getState()) {
			schedule(sessionId, job);
		} else if (JobState.CANCELLED == job.getState()) {
			cancel(sessionId, job);
		}
	}

	private void cancel(UUID sessionId, Job job) {
		logger.info("cancel job " + job.getJobId());
		JobCommand cmd = new JobCommand(sessionId, job.getJobId(), null, Command.CANCEL);
		pubSubServer.publish(cmd);
	}

	private void schedule(UUID sessionId, Job job) {
		logger.debug("schedule job " + job.getJobId());
		scheduledJobs.add(job.getJobId());
		JobCommand cmd = new JobCommand(sessionId, job.getJobId(), null, Command.SCHEDULE);
		pubSubServer.publish(cmd);
	}

	@Override
	public void onMessage(String message) {
		
		JobCommand compMsg = RestUtils.parseJson(JobCommand.class, message);
		
		switch (compMsg.getCommand()) {
		case OFFER:
			logger.debug("received an offer for job " + compMsg.getJobId() + " from comp " + compMsg.getCompId());
			// respond only to the first offer (remove() must be atomic)
			boolean firstOffer = scheduledJobs.remove(compMsg.getJobId());
			if (firstOffer) {
				logger.debug("choose offer " + compMsg.getCompId());
				pubSubServer.publish(new JobCommand(compMsg.getSessionId(), compMsg.getJobId(), compMsg.getCompId(), Command.CHOOSE));				
			}
			break;
		case BUSY:
			//TODO
			logger.info("comp busy");
			break;			
		case AVAILABLE:
			//TODO
			logger.info("comp available");
			break;

		default:
			logger.warn("unknown command: " + compMsg.getCommand());
		}
	}
}

