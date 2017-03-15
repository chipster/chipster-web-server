package fi.csc.chipster.scheduler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Iterator;
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
	
	Object jobsLock = new Object();
	HashMap<IdPair, LocalDateTime> newJobs = new HashMap<>();
	HashMap<IdPair, LocalDateTime> scheduledJobs = new HashMap<>();
	HashMap<IdPair, LocalDateTime> runningJobs = new HashMap<>();

	private long waitTimeout;
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
    	
    	String username = Config.USERNAME_SCHEDULER;
    	String password = config.getPassword(username);
    	
    	this.waitTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_TIMEOUT);
    	this.scheduleTimeout = config.getLong(Config.KEY_SCHEDULER_SCHEDULE_TIMEOUT);
    	this.heartbeatLostTimeout = config.getLong(Config.KEY_SCHEDULER_HEARTBEAT_LOST_TIMEOUT);
    	this.jobTimerInterval = config.getLong(Config.KEY_SCHEDULER_JOB_TIMER_INTERVAL) * 1000;
    	    	
		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password);	      
    	
    	this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials());
    	this.sessionDbClient.subscribe(SessionDb.JOBS_TOPIC, this, "scheduler-job-listener");    	
    	
    	this.pubSubServer = new PubSubServer(config.getString("scheduler-bind"), "events", authService, this, this, "scheduler-events");
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
		synchronized (jobsLock) {			
			
			List<Job> newDbJobs = sessionDbClient.getJobs(JobState.NEW);
			if (!newDbJobs.isEmpty()) {
				logger.info("found " + newDbJobs.size() + " waiting jobs from the session-db");
				for (Job job : newDbJobs) {				
					newJobs.put(new IdPair(job.getSession().getSessionId(), job.getJobId()), LocalDateTime.now());
				}
			}
			
			List<Job> runningDbJobs = sessionDbClient.getJobs(JobState.RUNNING);
			if (!runningDbJobs.isEmpty()) {
				logger.info("found " + runningDbJobs.size() + " running jobs from the session-db");
				for (Job job : runningDbJobs) {
					runningJobs.put(new IdPair(job.getSession().getSessionId(), job.getJobId()), LocalDateTime.now());
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
		} catch (RestException ex) {
			logger.error("failed to handle a session event", ex);
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
		synchronized (jobsLock) {				
			switch (e.getType()) {
			case CREATE:				
				Job job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
				switch (job.getState()) {
				case NEW:
					
					// when a client adds a new job, try to schedule it immediately
					
					logger.info("received a new job " + jobIdPair + ", trying to schedule it");
					newJobs.put(jobIdPair, LocalDateTime.now());
					schedule(job);
					break;
				default:
					break;
				}
				break;
	
			case UPDATE:				
				job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
				switch (job.getState()) {
				case CANCELLED:
					
					// when client cancels a job, clean up
					
					cancel(jobIdPair);
					break;
				case COMPLETED:
				case FAILED:
				case FAILED_USER_ERROR:
					
					// when the comp has finished the job, we can forget it
					
					logger.info("job finished " + jobIdPair);
					runningJobs.remove(jobIdPair);
					break;
				default:
					break;
				}
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
		synchronized (jobsLock) {			
		
			JobCommand compMsg = RestUtils.parseJson(JobCommand.class, message);
			IdPair jobIdPair = new IdPair(compMsg.getSessionId(), compMsg.getJobId());
			
			switch (compMsg.getCommand()) {
			case OFFER:
				
				// when comps offer to run a job, pick the first one
				
				logger.info("received an offer for job " + jobIdPair + " from comp " + asShort(compMsg.getCompId()));
				// respond only to the first offer
				if (scheduledJobs.remove(jobIdPair) != null) {
					run(compMsg, jobIdPair);
				}
				break;
			case BUSY:
				break;			
			case AVAILABLE:
				
				// when a comp has a free slot, try to schedule all waiting jobs
				
				logger.debug("comp available " + asShort(compMsg.getCompId()));
				scheduleNewJobs();
				break;
				
			case RUNNING:
				
				// update the heartbeat timestamps of the running jobs
				
				logger.debug("job running " + jobIdPair);
				runningJobs.put(jobIdPair, LocalDateTime.now());
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
		synchronized (jobsLock) {
			
			// expire waiting jobs if any comp haven't accepted it (either because of being full or missing the suitable tool)
			
			Iterator<IdPair> iter = newJobs.keySet().iterator();
			while (iter.hasNext()) {
				IdPair jobIdPair = iter.next();
				if (newJobs.get(jobIdPair).until(LocalDateTime.now(), ChronoUnit.SECONDS) > waitTimeout) {
					// iterator allows safe removal from the collection that is being iterated
					iter.remove();
					expire(jobIdPair, "wait time exceeded");
				}
			}
			
			// if a job isn't scheduled, move it back to NEW state for trying again later
			
			iter = scheduledJobs.keySet().iterator();
			while (iter.hasNext()) {
				IdPair jobIdPair = iter.next();
				if (scheduledJobs.get(jobIdPair).until(LocalDateTime.now(), ChronoUnit.SECONDS) > scheduleTimeout) {
					
					logger.warn("no offer for job " + jobIdPair + ", changing it from scheduled back to new");
					iter.remove();
					newJobs.put(jobIdPair, LocalDateTime.now());
				}
			}
			
			// if the the running job hasn't sent heartbeats for some time, something unexpected has happened for the
			// comp and the job is lost
			
			iter = runningJobs.keySet().iterator();
			while (iter.hasNext()) {
				IdPair jobIdPair = iter.next();			
				if (runningJobs.get(jobIdPair).until(LocalDateTime.now(), ChronoUnit.SECONDS) > heartbeatLostTimeout) {
					iter.remove();
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
	 * @param job
	 */
	private void schedule(Job job) {
		synchronized (jobsLock) {			
			IdPair jobId = new IdPair(job.getSession().getSessionId(), job.getJobId());
			newJobs.remove(jobId);
			scheduledJobs.put(jobId, LocalDateTime.now());
			
			JobCommand cmd = new JobCommand(jobId.getSessionId(), jobId.getJobId(), null, Command.SCHEDULE);
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
		synchronized (jobsLock) {
			logger.info("offer for job " + jobId + " chosen from comp " + asShort(compMsg.getCompId()));
			runningJobs.put(jobId, LocalDateTime.now());
			pubSubServer.publish(new JobCommand(compMsg.getSessionId(), compMsg.getJobId(), compMsg.getCompId(), Command.CHOOSE));
		}
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
		synchronized (jobsLock) {
			
			logger.info("cancel job " + jobId);
			
			newJobs.remove(jobId);
			scheduledJobs.remove(jobId);
			runningJobs.remove(jobId);
			
			JobCommand cmd = new JobCommand(jobId.getSessionId(), jobId.getJobId(), null, Command.CANCEL);
			pubSubServer.publish(cmd);
			try {
				sessionDbClient.deleteJob(jobId.getSessionId(), jobId.getJobId());
			} catch (RestException e) {
				logger.error("failed to delete a cancelled job", e);
			}
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
		try {
			// jobs sorted by age if start time is set
			List<Job> jobs = sessionDbClient.getJobs(JobState.NEW).stream()
					.sorted((j1, j2) -> {
						if (j1.getStartTime() != null) {
							return j1.getStartTime().compareTo(j2.getStartTime());
						}
						return 0;
					}).collect(Collectors.toList());
		
			if (jobs.size() > 0) {
				logger.info("rescheduling " + jobs.size() + " waiting jobs " );
				for (Job job : jobs) {					
					schedule(job);
				}
			}
		} catch (RestException e) {
			logger.error("failed to get new jobs", e);
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

