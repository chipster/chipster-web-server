package fi.csc.chipster.scheduler;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.websocket.DeploymentException;
import javax.websocket.MessageHandler;
import javax.ws.rs.ProcessingException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.scheduler.JobCommand.Command;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.toolbox.ToolboxClientComp;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.microarray.messaging.JobState;

public class Scheduler implements SessionEventListener, MessageHandler.Whole<String>, StatusSource {

	private Logger logger = LogManager.getLogger();
	
	@SuppressWarnings("unused")
	private String serviceId;	
	private Config config;

	private AuthenticationClient authService;
	private ServiceLocatorClient serviceLocator;
	private SessionDbClient sessionDbClient;
	private ToolboxClientComp toolbox;
	
	private long waitTimeout;
	private long waitRunnableTimeout;
	private long scheduleTimeout;
	private long heartbeatLostTimeout;
	private long jobTimerInterval;
	private int maxScheduledAndRunningSlotsPerUser;
	private long waitNewSlotsPerUserTimeout;
	private int maxNewSlotsPerUser;

	private Timer jobTimer;
	private PubSubServer pubSubServer;	
	private SchedulerJobs jobs = new SchedulerJobs();
	private HttpServer adminServer;
	
	public Scheduler(Config config) {
		this.config = config;
	}

    public void startServer() throws ServletException, DeploymentException, InterruptedException, RestException, IOException {
    	
    	String username = Role.SCHEDULER;
    	String password = config.getPassword(username);
    	
    	this.waitTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_TIMEOUT);
    	this.waitRunnableTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_RUNNABLE_TIMEOUT);
    	this.scheduleTimeout = config.getLong(Config.KEY_SCHEDULER_SCHEDULE_TIMEOUT);
    	this.heartbeatLostTimeout = config.getLong(Config.KEY_SCHEDULER_HEARTBEAT_LOST_TIMEOUT);
    	this.jobTimerInterval = config.getLong(Config.KEY_SCHEDULER_JOB_TIMER_INTERVAL) * 1000;
    	this.maxScheduledAndRunningSlotsPerUser = config.getInt(Config.KEY_SCHEDULER_MAX_SCHEDULED_AND_RUNNING_SLOTS_PER_USER);
    	this.maxNewSlotsPerUser = config.getInt(Config.KEY_SCHEDULER_MAX_NEW_SLOTS_PER_USER);
    	this.waitNewSlotsPerUserTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_NEW_SLOTS_PER_USER_TIMEOUT);
    	
		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());
		String toolboxUrl = this.serviceLocator.getInternalService(Role.TOOLBOX).getUri();
		this.toolbox = new ToolboxClientComp(toolboxUrl);

    	this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.SERVER);
    	this.sessionDbClient.subscribe(SessionDbTopicConfig.JOBS_TOPIC, this, "scheduler-job-listener");    	
    	
    	SchedulerTopicConfig topicConfig = new SchedulerTopicConfig(authService);
    	this.pubSubServer = new PubSubServer(config.getBindUrl(Role.SCHEDULER), "events", this, topicConfig, "scheduler-events");
    	this.pubSubServer.setIdleTimeout(config.getLong(Config.KEY_WEBSOCKET_IDLE_TIMEOUT));
    	this.pubSubServer.setPingInterval(config.getLong(Config.KEY_WEBSOCKET_PING_INTERVAL));
    	this.pubSubServer.start();	
    	    
    	logger.info("getting unfinished jobs from the session-db");
    	getStateFromDb();    	
    	
    	this.jobTimer = new Timer("job timer", true);
    	this.jobTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				// catch exceptions to keep the timer running
				try {
					handleJobTimer();
				} catch (Exception e) {
					logger.error("error in job timer", e);
				}
			}
    	}, jobTimerInterval, jobTimerInterval);
    	
    	logger.info("starting the admin rest server");
    	
    	this.adminServer = RestUtils.startAdminServer(
    			new AdminResource(this, pubSubServer), null, Role.SCHEDULER, config, authService, this.serviceLocator);
    	    	
    	logger.info("scheduler is up and running");    		
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
			
			List<IdPair> newDbJobs = sessionDbClient.getJobs(JobState.NEW);
			if (!newDbJobs.isEmpty()) {
				logger.info("found " + newDbJobs.size() + " waiting jobs from the session-db");				
				for (IdPair idPair : newDbJobs) {
					try {
						Job job = sessionDbClient.getJob(idPair.getSessionId(), idPair.getJobId());
						jobs.addNewJob(new IdPair(idPair.getSessionId(), idPair.getJobId()), job.getCreatedBy(), getSlots(job));
					} catch (RestException e) {
						logger.error("could not get a job " + asShort(idPair.getJobId()) + " from session-db", e);
					}
				}
			}
			
			List<IdPair> runningDbJobs = sessionDbClient.getJobs(JobState.RUNNING);
			if (!runningDbJobs.isEmpty()) {
				logger.info("found " + runningDbJobs.size() + " running jobs from the session-db");
				for (IdPair idPair : runningDbJobs) {
					try {
						Job job = sessionDbClient.getJob(idPair.getSessionId(), idPair.getJobId());
						jobs.addRunningJob(new IdPair(idPair.getSessionId(), idPair.getJobId()), job.getCreatedBy(), getSlots(job));
					} catch (RestException e) {
						logger.error("could not get a job " + asShort(idPair.getJobId()) + " from session-db", e);
					}
				}
			}			
		}
	}
	
	private int getSlots(Job job) {

		try {
			ToolboxTool tool = this.toolbox.getTool(job.getToolId());
			if (tool == null) {
				logger.info("tried to get the slot count of tool " + job.getToolId() + " from toolbox but the tool was not found, default to 1");
				return 1;
			}
			Integer slots = tool.getSadlDescription().getSlotCount();
			if (slots == null) {
				logger.info("tool " + job.getToolId() + " slots is null, default to 1");
				return 1;
			}
			return slots;
		} catch (ProcessingException | IOException e) {
			logger.warn("toolbox error when getting the slot count of tool " + job.getToolId() + ", default to 1", e);
			return 1;
		}
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

		RestUtils.shutdown("scheduler-admin", adminServer);
		
		try {
			sessionDbClient.close();
		} catch (IOException e) {
			logger.warn("failed to stop the session-db client", e);
		}		
		if (pubSubServer != null) {
			pubSubServer.stop();
		}
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
				Job job = null;
				try {
					job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
				} catch (RestException err) {
					logger.error("received a CREATE event of job " + asShort(e.getResourceId()) + ", but couldn't get it from session-db", e);
					break;
				}
				switch (job.getState()) {
				case NEW:
					
					// current comp probably doesn't support non-unique jobIds, but this shouldn't be problem
					// in practice when only finished jobs are copied
					if (jobs.containsJobId(jobIdPair.getJobId())) {					
						logger.info("received a new job " + jobIdPair + ", but non-unique jobIds are not supported");
						endJob(jobIdPair, JobState.ERROR, "non-unique jobId");
					} else {
						// when a client adds a new job, try to schedule it immediately
						logger.info("received a new job " + jobIdPair + ", trying to schedule it");
						JobSchedulingState jobState = jobs.addNewJob(jobIdPair, job.getCreatedBy(), getSlots(job));
						schedule(jobIdPair, jobState);
					}
					break;
				default:
					break;
				}
				break;
	
			case UPDATE:				
				job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
					
					// when the comp has finished the job, we can forget it
				if (job.getState().isFinishedByComp()) {
					logger.info("job finished by comp" + jobIdPair);
					jobs.remove(jobIdPair);
				} 
					
				// job has been cancelled, inform comps and remove from scheduler
				else if (job.getState() == JobState.CANCELLED) {
					cancel(jobIdPair);
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
				if (jobs.get(jobIdPair) != null) {				
					if (!jobs.get(jobIdPair).isRunning()) {
						jobs.get(jobIdPair).setRunningTimestamp();
						run(compMsg, jobIdPair);
					}
				} else {
					logger.warn("comp " + asShort(compMsg.getCompId()) + " sent a offer of an non-existing job " + asShort(jobIdPair.getJobId()));
				}
				break;
			case BUSY:
				// there is a comp that is able to run this job later
				logger.info("job " + jobIdPair + " is runnable on comp " + asShort(compMsg.getCompId()));
				if (jobs.get(jobIdPair) != null) {
					jobs.get(jobIdPair).setRunnableTimestamp();				
				} else {
					logger.warn("comp " + asShort(compMsg.getCompId()) + " sent a busy message of an non-existing job " + asShort(jobIdPair.getJobId()));
				}
				break;
				
			case AVAILABLE:
				
				// when a comp has a free slot, try to schedule all waiting jobs
				
				logger.debug("comp available " + asShort(compMsg.getCompId()));
				scheduleNewJobs();
				break;
				
			case RUNNING:
				
				// update the heartbeat timestamps of the running jobs
				
				logger.debug("job running " + jobIdPair);
				if (jobs.get(jobIdPair) != null) {
					jobs.get(jobIdPair).setRunningTimestamp();
				} else {
					logger.warn("comp " + asShort(compMsg.getCompId()) + " sent a heartbeat of an non-existing job " + asShort(jobIdPair.getJobId()));
				}
				
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
				JobSchedulingState jobState = jobs.get(jobIdPair);
				
				if (jobState.isUserLimitReached()) {
					if (jobState.getTimeSinceNew() > waitNewSlotsPerUserTimeout) {
						jobs.remove(jobIdPair);
						expire(jobIdPair, "The job couldn't run, because you had the maximum number of jobs running. Please try again after you other jobs have completed.");
					}
				} else if (jobState.isRunnable()) {
					if (jobState.getTimeSinceNew() > waitRunnableTimeout) {
						jobs.remove(jobIdPair);
						expire(jobIdPair, "There was no computing server available to run this job, please try again later");
					}
				} else {
					if (jobState.getTimeSinceNew() > waitTimeout) {
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
	 * @param jobIdPair 
	 * 
	 * @param jobIdPair
	 */
	private boolean schedule(IdPair jobIdPair, JobSchedulingState jobState) {
		synchronized (jobs) {

			int userNewCount = jobs.getNewSlots(jobState.getUserId());
			int userScheduledCount = jobs.getScheduledSlots(jobState.getUserId());
			int userRunningCount = jobs.getRunningSlots(jobState.getUserId());
			
			// check if user is allowed to run jobs of this size  
			if (jobState.getSlots() > this.maxScheduledAndRunningSlotsPerUser) {
				logger.info("user " + jobState.getUserId() + " doesn't have enough slots for the tool");
				IdPair idPair = new IdPair(jobIdPair.getSessionId(), jobIdPair.getJobId());
				this.jobs.remove(idPair);
				this.endJob(idPair, JobState.ERROR, "Not enough job slots. The tool requires " + jobState.getSlots() + " slots but your user account can have only " + this.maxScheduledAndRunningSlotsPerUser + " slots running.");
				return false;
			}
			
			// fail the job immediately if user has created thousands of new jobs  
			if (userNewCount + jobState.getSlots() > this.maxNewSlotsPerUser) {
				logger.info("max job count of new jobs (" + this.maxNewSlotsPerUser + ") reached by user " + jobState.getUserId());
				IdPair idPair = new IdPair(jobIdPair.getSessionId(), jobIdPair.getJobId());
				this.jobs.remove(idPair);
				this.endJob(idPair, JobState.ERROR, "Maximum number of new jobs per user (" + this.maxNewSlotsPerUser + ") reached.");
				return false;
			}			
			
			// allow the job to queue if normal personal limit of running jobs is reached 
			if (userScheduledCount + userRunningCount + jobState.getSlots() > this.maxScheduledAndRunningSlotsPerUser) {
				logger.info("max job count of running jobs (" + this.maxScheduledAndRunningSlotsPerUser + ") reached by user " + jobState.getUserId());
				// disable timeout checks
				jobState.setUserLimitReachedTimestamp(true);
				return false;
			}
			
			// schedule
			
			// re-enable timeout checks, in case the personal limit was reached earlier
			jobState.setUserLimitReachedTimestamp(false);
			
			jobState.setScheduleTimestamp();
			
			JobCommand cmd = new JobCommand(jobIdPair.getSessionId(), jobIdPair.getJobId(), null, Command.SCHEDULE);
			pubSubServer.publish(cmd);
			return true;
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
		logger.warn("max wait time reached for job " + jobId);
		endJob(jobId, JobState.EXPIRED_WAITING, reason);
	}
	
	/**
	 * Set dbState in the session-db
	 * 
	 * This should be used only when anything else isn't updating the job, e.g. NEW or EXPIRED_WAITING jobs.
	 * Job's end time is set to current time.
	 * 
	 * @param jobId
	 * @param reason
	 */
	private void endJob(IdPair jobId, JobState jobState, String reason) {
		try {			
			Job job = sessionDbClient.getJob(jobId.getSessionId(), jobId.getJobId());
			job.setEndTime(Instant.now());
			job.setState(jobState);
			job.setStateDetail("Job state " + jobState + " (" + reason + ")");
			sessionDbClient.updateJob(jobId.getSessionId(), job);
		} catch (RestException e) {
			logger.error("could not set an old job " + jobId + " to " + jobState, e);
		}
	}

	/**
	 * The job has been cancelled or deleted
	 * 
	 * Inform the comps to cancel the job and remove it from the scheduler. By doing this here
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
	 * @throws RestException 
	 */
	private void scheduleNewJobs() {		
		List<IdPair> newJobs = jobs.getNewJobs().entrySet().stream()
			.sorted((e1, e2) -> e1.getValue().getNewTimestamp().compareTo(e2.getValue().getNewTimestamp()))
			.map(e -> e.getKey())
			.collect(Collectors.toList());			
	
		if (newJobs.size() > 0) {
			logger.info("rescheduling " + newJobs.size() + " waiting jobs (" + jobs.getScheduledJobs().size() + " still being scheduled");
			for (IdPair idPair : newJobs) {
				JobSchedulingState jobState = jobs.get(idPair);
				schedule(idPair, jobState);				
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

	public Map<String, Object> getStatus() {
		HashMap<String, Object> status = new HashMap<>();
		status.put("newJobCount", jobs.getNewJobs().size());
		status.put("runningJobCount", jobs.getRunningJobs().size());
		status.put("scheduledJobCount", jobs.getScheduledJobs().size());
		return status;
	}
}
