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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.scheduler.JobCommand.Command;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.sessiondb.model.WorkflowJobPlan;
import fi.csc.chipster.sessiondb.model.WorkflowPlan;
import fi.csc.chipster.sessiondb.model.WorkflowRun;
import fi.csc.chipster.toolbox.ToolboxClientComp;
import fi.csc.microarray.messaging.JobState;

public class WorkflowScheduler implements SessionEventListener, MessageHandler.Whole<String>, StatusSource {

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
	private SchedulerWorkflowRuns workflowRuns = new SchedulerWorkflowRuns();
	
	public WorkflowScheduler(Config config) {
		this.config = config;
	}

    public WorkflowScheduler(ServiceLocatorClient serviceLocator, AuthenticationClient authService,
			SessionDbClient sessionDbClient, Config config) throws ServletException, DeploymentException, InterruptedException, RestException, IOException {
		this.authService = authService;
		this.serviceLocator = serviceLocator;
		this.sessionDbClient = sessionDbClient;
		this.config = config;
		
		this.startServer();
	}

	public void startServer() throws ServletException, DeploymentException, InterruptedException, RestException, IOException {
    	    	
    	this.waitTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_TIMEOUT);
    	this.waitRunnableTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_RUNNABLE_TIMEOUT);
    	this.scheduleTimeout = config.getLong(Config.KEY_SCHEDULER_SCHEDULE_TIMEOUT);
    	this.heartbeatLostTimeout = config.getLong(Config.KEY_SCHEDULER_HEARTBEAT_LOST_TIMEOUT);
    	this.jobTimerInterval = config.getLong(Config.KEY_SCHEDULER_JOB_TIMER_INTERVAL) * 1000;
    	this.maxScheduledAndRunningSlotsPerUser = config.getInt(Config.KEY_SCHEDULER_MAX_SCHEDULED_AND_RUNNING_SLOTS_PER_USER);
    	this.maxNewSlotsPerUser = config.getInt(Config.KEY_SCHEDULER_MAX_NEW_SLOTS_PER_USER);
    	this.waitNewSlotsPerUserTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_NEW_SLOTS_PER_USER_TIMEOUT);
    	
		String toolboxUrl = this.serviceLocator.getInternalService(Role.TOOLBOX).getUri();
		this.toolbox = new ToolboxClientComp(toolboxUrl);

    	this.sessionDbClient.subscribe(SessionDbTopicConfig.WORKFLOW_RUNS_TOPIC, this, "scheduler-workflow-listener");    	
    	    	    
    	logger.info("getting unfinished workflows from the session-db");
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
    	    	    	
    	logger.info("workflow scheduler is up and running");    		
    }
    
	/**
	 * Get unfinished workflows from the session-db
	 * 
	 * The unfinished workflows in the db are always in NEW or RUNNING state.   
	 * 
	 * @throws RestException
	 */
	private void getStateFromDb() throws RestException {
		synchronized (workflowRuns) {			
			
			List<IdPair> newDbWorkflows = sessionDbClient.getWorkflowRuns(JobState.NEW);
			if (!newDbWorkflows.isEmpty()) {
				logger.info("found " + newDbWorkflows.size() + " waiting workflows from the session-db");				
				for (IdPair idPair : newDbWorkflows) {
					try {
						WorkflowRun run = sessionDbClient.getWorkflowRun(idPair.getSessionId(), idPair.getJobId());
						workflowRuns.addNew(new IdPair(idPair.getSessionId(), idPair.getJobId()), run.getCreatedBy());
					} catch (RestException e) {
						logger.error("could not get a workflows " + asShort(idPair.getJobId()) + " from session-db", e);
					}
				}
			}
			
			List<IdPair> runningDbWorkflows = sessionDbClient.getWorkflowRuns(JobState.RUNNING);
			if (!runningDbWorkflows.isEmpty()) {
				logger.info("found " + runningDbWorkflows.size() + " running workflows from the session-db");
				for (IdPair idPair : runningDbWorkflows) {
					try {
						WorkflowRun run = sessionDbClient.getWorkflowRun(idPair.getSessionId(), idPair.getJobId());
						workflowRuns.addRunning(new IdPair(idPair.getSessionId(), idPair.getJobId()), run.getCreatedBy());
					} catch (RestException e) {
						logger.error("could not get a workflows " + asShort(idPair.getJobId()) + " from session-db", e);
					}
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
    	        
        WorkflowScheduler server = new WorkflowScheduler(new Config());
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
	}

	@Override
	public void onEvent(SessionEvent e) {
		logger.debug("received a workflow event: " + e.getResourceType() + " " + e.getType());
		try {			
			if (e.getResourceType() == ResourceType.WORKFLOW_RUN) {
				handleWorkflowEvent(e, new IdPair(e.getSessionId(), e.getResourceId()));
			}
			if (e.getResourceType() == ResourceType.JOB) {
				handleJobEvent(e, new IdPair(e.getSessionId(), e.getResourceId()));
			}
		} catch (Exception ex) {
			logger.error("error when handling a session event", ex);
		}
	}	
	
	/**
	 * React to events from the session-db
	 * 
	 * @param e
	 * @param idPair
	 * @throws RestException
	 */
	private void handleWorkflowEvent(SessionEvent e, IdPair idPair) throws RestException {
		synchronized (workflowRuns) {				
			switch (e.getType()) {
			case CREATE:
				WorkflowRun run = null;
				try {
					run = sessionDbClient.getWorkflowRun(e.getSessionId(), e.getResourceId());
				} catch (RestException err) {
					logger.error("received a CREATE event of workflow run " + asShort(e.getResourceId()) + ", but couldn't get it from session-db", e);
					break;
				}
				switch (run.getState()) {
				case NEW:
					
					// when a client adds a new workflow run, try to run it immediately
					logger.info("received a new workflow run " + idPair + ", trying to schedule it");
					JobSchedulingState state = workflowRuns.addNew(idPair, run.getCreatedBy());
					schedule(idPair, state);
					
					break;
				default:
					break;
				}
				break;
	
			case UPDATE:
				run = sessionDbClient.getWorkflowRun(e.getSessionId(), e.getResourceId());
				if (run.getState() == JobState.CANCELLED) {
					cancel(idPair);
				}
				break;
			case DELETE:
				cancel(idPair);				
				break;
			default:
				break;
			}
		}
	}
	
	private void handleJobEvent(SessionEvent e, IdPair jobIdPair) throws RestException {
		synchronized (workflowRuns) {			
			
			switch (e.getType()) {
			case CREATE:
				// nothing to do
				break;
				
			case UPDATE:
				Job job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
				if (job.getState() == JobState.)
				break;
				
			case DELETE:
				
				break;
				
			default:
				break;
			}
		}
	}
	
	/**
	 * Timer for checking the timeouts
	 */
	private void handleJobTimer() {
		synchronized (workflowRuns) {
			
			// expire waiting jobs if any comp haven't accepted it (either because of being full or missing the suitable tool)				
			
			for (IdPair jobIdPair : workflowRuns.getNewJobs().keySet()) {
				JobSchedulingState jobState = workflowRuns.get(jobIdPair);
				
				if (jobState.isUserLimitReached()) {
					if (jobState.getTimeSinceNew() > waitNewSlotsPerUserTimeout) {
						workflowRuns.remove(jobIdPair);
						expire(jobIdPair, "The job couldn't run, because you had the maximum number of jobs running. Please try again after you other jobs have completed.");
					}
				} else if (jobState.isRunnable()) {
					if (jobState.getTimeSinceNew() > waitRunnableTimeout) {
						workflowRuns.remove(jobIdPair);
						expire(jobIdPair, "There was no computing server available to run this job, please try again later");
					}
				} else {
					if (jobState.getTimeSinceNew() > waitTimeout) {
						workflowRuns.remove(jobIdPair);
						expire(jobIdPair, "There was no computing server available to run this job, please inform server maintainers");
					}
				}
			}
			
			// if a job isn't scheduled, move it back to NEW state for trying again later			
			
			for (IdPair jobIdPair : workflowRuns.getScheduledJobs().keySet()) {
				if (workflowRuns.get(jobIdPair).getTimeSinceScheduled() > scheduleTimeout) {
					workflowRuns.get(jobIdPair).removeScheduled();
				}
			}
			
			// if the the running job hasn't sent heartbeats for some time, something unexpected has happened for the
			// comp and the job is lost
			
			for (IdPair jobIdPair : workflowRuns.getRunningJobs().keySet()) {
				if (workflowRuns.get(jobIdPair).getTimeSinceLastHeartbeat() > heartbeatLostTimeout) {
					workflowRuns.remove(jobIdPair);
					expire(jobIdPair, "heartbeat lost");
				}
			}
		}
	}
	
	private boolean schedule(IdPair idPair, JobSchedulingState jobState) {
		synchronized (workflowRuns) {
			
			WorkflowRun run = sessionDbClient.getWorkflowRun(idPair.getSessionId(), idPair.getJobId());
			
			WorkflowPlan plan = sessionDbClient.getWorkflowPlan(idPair.getSessionId(), run.getWorkflowPlanId());
			
			if (run.getCurrentWorkflowJobPlanId() != null) {
				this.endJob(idPair, JobState.ERROR, "The workflow isn't new");
				return false;
			}
			
			if (plan.getWorkflowJobPlans().isEmpty()) {
				this.endJob(idPair, JobState.ERROR, "The workflow is empty");
				return false;
			}
			
			WorkflowJobPlan jobPlan = plan.getWorkflowJobPlans().get(0);
			
			run.setCurrentWorkflowJobPlanId(jobPlan.getWorkflowJobPlanId());
			
			Job job = new Job();
			
			job.setCreatedBy(run.getCreatedBy());
			job.setInputs(jobPlan.getInputs());
			job.setParameters(jobPlan.getParameters());
			job.setMetadataFiles(jobPlan.getMetadataFiles());
			job.setModule(jobPlan.getModule());
			job.setState(JobState.NEW);
			job.setToolCategory(jobPlan.getToolCategory());
			job.setToolId(jobPlan.getToolId());
			job.setToolName(jobPlan.getToolName());
			
			UUID jobId = sessionDbClient.createJob(idPair.getSessionId(), job);
			
			//FXIME the name should be currentJobId
			run.setCurrentJobPlanId(jobId.toString());
			
			sessionDbClient.updateWorkflowRun(run);
					
			return true;
		}
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
		synchronized (workflowRuns) {
			
			logger.info("cancel job " + jobId);			
			workflowRuns.remove(jobId);
			
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
		List<IdPair> newJobs = workflowRuns.getNewJobs().entrySet().stream()
			.sorted((e1, e2) -> e1.getValue().getNewTimestamp().compareTo(e2.getValue().getNewTimestamp()))
			.map(e -> e.getKey())
			.collect(Collectors.toList());			
	
		if (newJobs.size() > 0) {
			logger.info("rescheduling " + newJobs.size() + " waiting jobs (" + workflowRuns.getScheduledJobs().size() + " still being scheduled");
			for (IdPair idPair : newJobs) {
				JobSchedulingState jobState = workflowRuns.get(idPair);
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
		status.put("newWorkflowCount", workflowRuns.getNew().size());
		status.put("runningWorkflowCount", workflowRuns.getRunning().size());
		return status;
	}
}
