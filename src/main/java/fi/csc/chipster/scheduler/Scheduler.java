package fi.csc.chipster.scheduler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.stream.Collectors;

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
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDb;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.SessionEvent;
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
	
	private SchedulerJobs jobs = new SchedulerJobs();
	
	private long waitTimeout;
	private long waitRunnableTimeout;
	private long scheduleTimeout;
	private long heartbeatLostTimeout;
	private long jobTimerInterval;

	private Timer jobTimer;
	
	public Scheduler(Config config) {
		this.config = config;
	}

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this application.
     * @return Grizzly HTTP server.
     * @throws DeploymentException 
     * @throws ServletException 
     * @throws InterruptedException 
     * @throws RestException 
     * @throws IOException 
     * @throws Exception 
     */
    public void startServer() throws ServletException, DeploymentException, InterruptedException, RestException, IOException {
    	
    	String username = Role.SCHEDULER;
    	String password = config.getPassword(username);
    	
    	this.waitTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_TIMEOUT);
    	this.waitRunnableTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_RUNNABLE_TIMEOUT);
    	this.scheduleTimeout = config.getLong(Config.KEY_SCHEDULER_SCHEDULE_TIMEOUT);
    	this.heartbeatLostTimeout = config.getLong(Config.KEY_SCHEDULER_HEARTBEAT_LOST_TIMEOUT);
    	this.jobTimerInterval = config.getLong(Config.KEY_SCHEDULER_JOB_TIMER_INTERVAL) * 1000;
    	    	
		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);	      
    	
    	this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials());
    	this.sessionDbClient.subscribe(SessionDb.JOBS_TOPIC, this, "scheduler-job-listener");    	
    	
    	this.pubSubServer = new PubSubServer(config.getBindUrl(Role.SCHEDULER), "events", authService, this, this, "scheduler-events");
    	this.pubSubServer.start();	
    	    
    	logger.info("getting unfinished jobs from the session-db");
    	getStateFromDb();    	
    	
    	this.jobTimer = new Timer();
    	this.jobTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				handleJobTimer();
			}
    	}, jobTimerInterval, jobTimerInterval);
    	
    	logger.info("scheduler is up and running");    		
    }
    
    @Override
	public boolean isAuthorized(AuthPrincipal principal, String topic) {
		// check the authorization of the connecting comps
		return principal.getRoles().contains(Role.COMP);			
	}
    
	/**
	 * Get unfinished jobs from the session-db
	 * 
	 * The unfinished jobs in the db are always in NEW or RUNNING state. The SCHEDULED state 
	 * isn't updated to the db, because changing state so fast would cause only more harm.   
	 * 
	 * @throws RestException
	 */
	private void getStateFromDb() throws RestException {
		synchronized (jobs) {			
			
			List<Job> newDbJobs = sessionDbClient.getJobs(JobState.NEW);
			if (!newDbJobs.isEmpty()) {
				logger.info("found " + newDbJobs.size() + " waiting jobs from the session-db");
				for (Job job : newDbJobs) {				
					jobs.addNewJob(new IdPair(job.getSession().getSessionId(), job.getJobId()));
				}
			}
			
			List<Job> runningDbJobs = sessionDbClient.getJobs(JobState.RUNNING);
			if (!runningDbJobs.isEmpty()) {
				logger.info("found " + runningDbJobs.size() + " running jobs from the session-db");
				for (Job job : runningDbJobs) {
					jobs.addRunningJob(new IdPair(job.getSession().getSessionId(), job.getJobId()));
				}
			}			
		}
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
		try {			
			if (e.getResourceType() == ResourceType.JOB) {
				handleDbEvent(e, new IdPair(e.getSessionId(), e.getResourceId()));
			}
		} catch (Exception ex) {
			logger.error("error when handling a session event", ex);
		}
	}	
	
	/**
	 * React to events from the session-db
	 * 
	 * @param e
	 * @param jobIdPair
	 * @throws RestException
	 */
	private void handleDbEvent(SessionEvent e, IdPair jobIdPair) throws RestException {
		synchronized (jobs) {				
			switch (e.getType()) {
			case CREATE:				
				Job job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
				switch (job.getState()) {
				case NEW:
					
					// when a client adds a new job, try to schedule it immediately
					
					logger.info("received a new job " + jobIdPair + ", trying to schedule it");
					jobs.addNewJob(jobIdPair);
					schedule(jobIdPair);
					break;
				default:
					break;
				}
				break;
	
			case UPDATE:				
				job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
				switch (job.getState()) {					
				case COMPLETED:
				case FAILED:
				case FAILED_USER_ERROR:
					
					// when the comp has finished the job, we can forget it
					
					logger.info("job finished " + jobIdPair);
					jobs.remove(jobIdPair);
					break;
				default:
					break;
				}
				break;
			case DELETE:
				cancel(jobIdPair);				
				break;
			default:
				break;
			}
		}
	}
	
	/* 
	 * React to events from comps
	 */
	@Override
	public void onMessage(String message) {
		synchronized (jobs) {			
		
			JobCommand compMsg = RestUtils.parseJson(JobCommand.class, message);
			IdPair jobIdPair = new IdPair(compMsg.getSessionId(), compMsg.getJobId());
			
			switch (compMsg.getCommand()) {
			case OFFER:
				
				// when comps offer to run a job, pick the first one
				
				logger.info("received an offer for job " + jobIdPair + " from comp " + asShort(compMsg.getCompId()));
				// respond only to the first offer
				if (!jobs.get(jobIdPair).isRunning()) {
					jobs.get(jobIdPair).setRunningTimestamp();
					run(compMsg, jobIdPair);
				}
				break;
			case BUSY:
				// there is a comp that is able to run this job later
				logger.info("job " + jobIdPair + " is runnable on comp " + asShort(compMsg.getCompId()));
				jobs.get(jobIdPair).setRunnableTimestamp();
				break;
				
			case AVAILABLE:
				
				// when a comp has a free slot, try to schedule all waiting jobs
				
				logger.debug("comp available " + asShort(compMsg.getCompId()));
				scheduleNewJobs();
				break;
				
			case RUNNING:
				
				// update the heartbeat timestamps of the running jobs
				
				logger.debug("job running " + jobIdPair);
				jobs.get(jobIdPair).setRunningTimestamp();
				break;
	
			default:
				logger.warn("unknown command: " + compMsg.getCommand());
			}
		}
	}
	
	/**
	 * Timer for checking the timeouts
	 */
	private void handleJobTimer() {
		synchronized (jobs) {
			
			// expire waiting jobs if any comp haven't accepted it (either because of being full or missing the suitable tool)				
			
			for (IdPair jobIdPair : jobs.getNewJobs().keySet()) {
				if (jobs.get(jobIdPair).isRunnable()) {
					if (jobs.get(jobIdPair).getTimeSinceNew() > waitRunnableTimeout) {
						jobs.remove(jobIdPair);
						expire(jobIdPair, "There was no computing server available to run this job, please try again later");
					}
				} else {
					if (jobs.get(jobIdPair).getTimeSinceNew() > waitTimeout) {
						jobs.remove(jobIdPair);
						expire(jobIdPair, "There was no computing server available to run this job, please inform server maintainers");
					}
				}
			}
			
			// if a job isn't scheduled, move it back to NEW state for trying again later			
			
			for (IdPair jobIdPair : jobs.getScheduledJobs().keySet()) {
				if (jobs.get(jobIdPair).getTimeSinceScheduled() > scheduleTimeout) {
					jobs.get(jobIdPair).removeScheduled();
				}
			}			
			
			// if the the running job hasn't sent heartbeats for some time, something unexpected has happened for the
			// comp and the job is lost
			
			for (IdPair jobIdPair : jobs.getRunningJobs().keySet()) {
				if (jobs.get(jobIdPair).getTimeSinceLastHeartbeat() > heartbeatLostTimeout) {
					jobs.remove(jobIdPair);
					expire(jobIdPair, "heartbeat lost");
				}
			}
		}
	}
	
	/**
	 * Move from NEW to SCEHDULED
	 * 
	 * No need to update the db, because saving the SCHEDULE state would only make it more difficult 
	 * to resolve the job states when the scheduler is started.
	 * 
	 * @param jobIdPair
	 */
	private void schedule(IdPair jobIdPair) {
		synchronized (jobs) {			
			jobs.get(jobIdPair).setScheduleTimestamp();
			
			JobCommand cmd = new JobCommand(jobIdPair.getSessionId(), jobIdPair.getJobId(), null, Command.SCHEDULE);
			pubSubServer.publish(cmd);
		}
	}
		
	/**
	 * Move from SCHEDULED to RUNNING
	 * 
	 * @param compMsg
	 * @param jobId
	 */
	private void run(JobCommand compMsg, IdPair jobId) {
		logger.info("offer for job " + jobId + " chosen from comp " + asShort(compMsg.getCompId()));			
		pubSubServer.publish(new JobCommand(compMsg.getSessionId(), compMsg.getJobId(), compMsg.getCompId(), Command.CHOOSE));
	}

	/**
	 * Move from NEW to EXPIRED_WAITING
	 * 
	 * @param jobId
	 * @param reason
	 */
	private void expire(IdPair jobId, String reason) {
		try {			
			Job job = sessionDbClient.getJob(jobId.getSessionId(), jobId.getJobId());
			logger.warn("max wait time reached for job " + jobId);
			job.setEndTime(LocalDateTime.now());
			job.setState(JobState.EXPIRED_WAITING);
			job.setStateDetail("There was no computing server available to run this job, please try again later on (" + reason + ")");
			sessionDbClient.updateJob(job.getSession().getSessionId(), job);
		} catch (RestException e) {
			logger.error("could not set an old job " + jobId + " to expired", e);
		}
	}

	/**
	 * The client has cancelled the job
	 * 
	 * Inform the comps to cancel the job and remove it from the db. By doing this here
	 * in the scheduler we can handle both waiting and running jobs.
	 * 
	 * @param jobId
	 */
	private void cancel(IdPair jobId) {
		synchronized (jobs) {
			
			logger.info("cancel job " + jobId);			
			jobs.remove(jobId);
			
			JobCommand cmd = new JobCommand(jobId.getSessionId(), jobId.getJobId(), null, Command.CANCEL);
			pubSubServer.publish(cmd);
		}
	}
		
	/**
	 * Get all waiting jobs and try to schedule them
	 * 
	 * We have to send all of them, because we can't know what kind of jobs are accepted by different comps.
	 * 
	 * The oldest jobs jobs are sent first although the previous failed attempts have most likely already
	 * reseted the timestamp.
	 */
	private void scheduleNewJobs() {		
		List<IdPair> newJobs = jobs.getNewJobs().entrySet().stream()
			.sorted((e1, e2) -> e1.getValue().getNewTimestamp().compareTo(e2.getValue().getNewTimestamp()))
			.map(e -> e.getKey())
			.collect(Collectors.toList());			
	
		if (newJobs.size() > 0) {
			logger.info("rescheduling " + newJobs.size() + " waiting jobs (" + jobs.getScheduledJobs().size() + " still being scheduled");
			for (IdPair job : newJobs) {					
				schedule(job);
			}
		}
	}
	
	/**
	 * First 4 letters of the UUID for log messages 
	 * 
	 * @param id
	 * @return
	 */
	private String asShort(UUID id) {
		return id.toString().substring(0, 4);
	}
}
