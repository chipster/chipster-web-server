package fi.csc.chipster.scheduler;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.websocket.DeploymentException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.WorkflowJob;
import fi.csc.chipster.sessiondb.model.WorkflowRun;
import fi.csc.chipster.sessiondb.model.WorkflowRunIdPair;
import fi.csc.chipster.sessiondb.model.WorkflowState;
import fi.csc.microarray.messaging.JobState;

public class WorkflowScheduler implements StatusSource {

	private Logger logger = LogManager.getLogger();
		
	private Config config;

	private SessionDbClient sessionDbClient;
	

	private Timer timer;
	private SchedulerWorkflowRuns workflowRuns = new SchedulerWorkflowRuns();
	
	public static final String KEY_RUNNING_TIMEOUT = "workflow-scheduler-running-timeout";
	public static final String KEY_DRAINING_TIMEOUT = "workflow-scheduler-draining-timeout";
	public static final String KEY_CANCELLING_TIMEOUT = "workflow-scheduler-cancelling-timeout";
	public static final String KEY_TIMER_INTERVAL = "workflow-scheduler-timer-interval";
	public static final String KEY_MAX_WORKFLOW_RUNS_PER_USER = "workflow-scheduler-max-workflow-runs-per-user";
	
	enum DrainMode {
		IGNORE,
		BAIL_OUT,
		DRAIN,
	}
	
	private DrainMode onError = DrainMode.DRAIN;

	private long timerInterval;
	private long maxWorkflowRUnsPerUser;	
	private long runningTimeout;
	private long drainingTimeout;
	private long cancelTimeout;
	
	public WorkflowScheduler(Config config) {
		this.config = config;
	}

    public WorkflowScheduler(ServiceLocatorClient serviceLocator, AuthenticationClient authService,
			SessionDbClient sessionDbClient, Config config) throws ServletException, DeploymentException, InterruptedException, RestException, IOException {
		this.sessionDbClient = sessionDbClient;
		this.config = config;
		
		this.startServer();
	}

	public void startServer() throws ServletException, DeploymentException, InterruptedException, RestException, IOException {
    	    	
    	this.runningTimeout = config.getLong(KEY_RUNNING_TIMEOUT);
    	this.drainingTimeout = config.getLong(KEY_DRAINING_TIMEOUT);
    	this.cancelTimeout = config.getLong(KEY_CANCELLING_TIMEOUT);
    	this.timerInterval = config.getLong(KEY_TIMER_INTERVAL) * 1000;
    	this.maxWorkflowRUnsPerUser = config.getLong(KEY_MAX_WORKFLOW_RUNS_PER_USER);    	

    	this.sessionDbClient.subscribe(SessionDbTopicConfig.JOBS_TOPIC, new JobEventLister(), "workflow-scheduler-job-listener");
    	this.sessionDbClient.subscribe(SessionDbTopicConfig.WORKFLOW_RUNS_TOPIC, new WorkflowEventLister(), "workflow-scheduler-workflow-listener");
    	    	    
    	logger.info("getting unfinished workflows from the session-db");
    	getStateFromDb();    	
    	
    	this.timer = new Timer("job timer", true);
    	this.timer.schedule(new TimerTask() {
			@Override
			public void run() {
				// catch exceptions to keep the timer running
				try {
					handleWorkflowTimer();
				} catch (Exception e) {
					logger.error("error in job timer", e);
				}
			}
    	}, timerInterval, timerInterval);
    	    	    	
    	logger.info("workflow scheduler is up and running");    		
    }
    
	/**
	 * Get unfinished workflows from the session-db
	 * 
	 * The unfinished workflows in the db are always in NEW, RUNNING or DRAINING state.   
	 * 
	 * @throws RestException
	 */
	private void getStateFromDb() throws RestException {
		synchronized (workflowRuns) {						
			for (WorkflowState state : Arrays.asList(new WorkflowState[] { WorkflowState.NEW, WorkflowState.RUNNING, WorkflowState.DRAINING, WorkflowState.CANCELLING })) {
				List<WorkflowRunIdPair> runs = sessionDbClient.getWorkflowRuns(state);
				if (!runs.isEmpty()) {
					logger.info("found " + runs.size() + " workflows from the session-db in state " + state);				
					for (WorkflowRunIdPair runIdPair : runs) {
						updateWorkflowRun(runIdPair);						
					}
				}
			}			
		}
	}

	public void close() {
	}
	
	public class WorkflowEventLister implements SessionEventListener {
		@Override
		public void onEvent(SessionEvent e) {
			logger.debug("received a workflow event: " + e.getResourceType() + " " + e.getType());
			try {							
				handleWorkflowEvent(e, new WorkflowRunIdPair(e.getSessionId(), e.getResourceId()));				
			} catch (Exception ex) {
				logger.error("error when handling a workflow run event", ex);
			}
		}
	}
	
	public class JobEventLister implements SessionEventListener {
		@Override
		public void onEvent(SessionEvent e) {
			logger.debug("received a workflow event: " + e.getResourceType() + " " + e.getType());
			try {			
				handleJobEvent(e, new IdPair(e.getSessionId(), e.getResourceId()));
			} catch (Exception ex) {
				logger.error("error when handling a job event", ex);
			}
		}
	}
	
	/**
	 * React to workflow events from the session-db
	 * 
	 * @param e
	 * @param runIdPair
	 * @throws RestException
	 */
	private void handleWorkflowEvent(SessionEvent e, WorkflowRunIdPair runIdPair) throws RestException {
		switch (e.getType()) {
		case CREATE:
			logger.info("workflow run " + runIdPair + " was created");
			updateWorkflowRun(runIdPair);
			break;
		case UPDATE:
			logger.info("workflow run " + runIdPair + " update " + e.getState());
			if (WorkflowState.valueOf(e.getState()) == WorkflowState.CANCELLING) {
				logger.info("workflow run " + runIdPair + " is cancelling");
				WorkflowSchedulingState schedulingState = workflowRuns.get(runIdPair);
				if (schedulingState == null) {
					logger.warn("scheduling state of cancelling workflow run " + runIdPair + " is missing");
					break;
				}
				schedulingState.setState(WorkflowState.CANCELLING);
				
				// try get stateDetail from session-db
				WorkflowRun run = this.sessionDbClient.getWorkflowRun(runIdPair.getSessionId(), runIdPair.getWorkflowRunId());
				if (run.getState() == WorkflowState.CANCELLING) {
					schedulingState.setStateDetail(run.getStateDetail());
				}
				this.updateWorkflowRun(runIdPair);
			}
			break;
		case DELETE:
			logger.info("workflow run " + runIdPair + " was deleted");
			if (workflowRuns.get(runIdPair) != null) {
				// no need to update the session-db, just cancel the jobs
				this.cancelWorkflowRunJobs(runIdPair);
			}
			break;
		default:
			break;
		}
	}
	
	private WorkflowSchedulingState run(WorkflowRun run) throws WorkflowException {
		synchronized (workflowRuns) {
			
			if (workflowRuns.getByUserId(run.getCreatedBy()).size() > this.maxWorkflowRUnsPerUser) {
				throw new WorkflowException("The workflow couldn't run, because you had the maximum number of workflows running. "
						+ "Please try again after another workflow has finished.", WorkflowState.FAILED);
			}
			
			WorkflowSchedulingState schedulingState = new WorkflowSchedulingState(run.getCreatedBy());
			schedulingState.setState(WorkflowState.RUNNING);
			schedulingState.setWorkflowJobs(run.getWorkflowJobs());			
			
			workflowRuns.put(run.getWorkflowRunIdPair(), schedulingState);
			
			return schedulingState;
		}
	}

	private void handleJobEvent(SessionEvent e, IdPair jobIdPair) throws RestException {
		synchronized (workflowRuns) {			
			
			switch (e.getType()) {
			case CREATE:
				// nothing to do
				break;
				
			case UPDATE:
				JobState jobState = JobState.valueOf(e.getState());
					
					WorkflowRunIdPair runIdPair = this.workflowRuns.getWorkflowRunId(jobIdPair);										
					
					// if a workflow job
					if (runIdPair != null) {
						
						// update jobs state
						WorkflowSchedulingState schedulingState = workflowRuns.get(runIdPair);
						schedulingState.putJobId(jobIdPair.getJobId(), jobState);
						
						if (jobState.isFinished()) {
																				
							logger.info("workflow run " + runIdPair + " job " + jobIdPair + " state " + jobState);
							
							if (JobState.COMPLETED == jobState) {
								try {
									updateInputBindings(runIdPair, schedulingState.getWorkflowJobs());
									
								} catch (BindException ex) {				
									this.handleJobFail(runIdPair, WorkflowState.ERROR, ex.getMessage());
								}
							} else if (JobState.CANCELLED == jobState) {
								// nothing to do, just update the workflow run 
							} else {
								// job was unsuccessful or cancelled						
								handleJobFail(runIdPair, WorkflowState.FAILED, "job " + jobIdPair + " state " + jobState);
							}
							updateWorkflowRun(runIdPair);
						}
					}
				break;
				
			case DELETE:
				runIdPair = this.workflowRuns.getWorkflowRunId(jobIdPair);
				
				// if the job is part of some workflow
				if (runIdPair != null) {
					/* Someone deleted the job
					 * - Session deletion should be the only way to do this in the UI (cancellation is 
					 *   implemented with JobState.CANCELLED)
					 * - Remove it from our bookkeeping
					 * - Don't do anything for the WorkflowRun. The one who deleted this job should take care of anything else.
					 */					
					logger.info("job " + jobIdPair + " is deleted");
					this.workflowRuns.get(runIdPair).removeJobId(jobIdPair.getJobId());
				}
				break;
				
			default:
				break;
			}
		}
	}
	
	/**
	 * Timer for checking the timeouts
	 */
	private void handleWorkflowTimer() {
		synchronized (workflowRuns) {			
			
			// this shouldn't be possible, because all workflows are run immediately
			this.checkTimeouts(WorkflowState.NEW, 10, WorkflowState.CANCELLING);
			this.checkTimeouts(WorkflowState.RUNNING, runningTimeout, WorkflowState.CANCELLING);
			this.checkTimeouts(WorkflowState.DRAINING, drainingTimeout, WorkflowState.CANCELLING);			
			this.checkTimeouts(WorkflowState.CANCELLING, cancelTimeout, WorkflowState.ERROR);
		}
	}
	
	private void checkTimeouts(WorkflowState stateNow, long timeoutSeconds, WorkflowState timeoutState) {
		for (WorkflowRunIdPair runIdPair : workflowRuns.get(stateNow)) {
			WorkflowSchedulingState schedulingState = workflowRuns.get(runIdPair);
			
			//FIXME store state change times
			if (schedulingState.getTimeSince(stateNow) > timeoutSeconds) {
				
				logger.info(stateNow + " timeout for workflow run " + runIdPair);
				schedulingState.setState(timeoutState);
				schedulingState.setStateDetail("the workflow max time reached for state " + stateNow + " (" + timeoutSeconds + " seconds)");
				updateWorkflowRun(runIdPair);
			}
		}
	}
	
	public void updateWorkflowRun(WorkflowRunIdPair runIdPair) {
		synchronized (workflowRuns) {
			WorkflowSchedulingState schedulingState = this.workflowRuns.get(runIdPair);
			
			try {
				// if new
				if (schedulingState == null) {
					WorkflowRun run;
					try {
						run = this.sessionDbClient.getWorkflowRun(runIdPair.getSessionId(), runIdPair.getWorkflowRunId());
						schedulingState = run(run);
						
					} catch (RestException e) {
						logger.error("failed to get the workflow run", e);
					}					
				}
				
				if (WorkflowState.CANCELLING == schedulingState.getState()) {
					if (schedulingState.getUnfinishedJobs().isEmpty()) {
						// after all jobs are finished, set this run to a finished state 
						schedulingState.setState(WorkflowState.CANCELLED);
					} else {
						// cancel all remaining jobs
						cancelWorkflowRunJobs(runIdPair);
					}
				}
				
				if (WorkflowState.RUNNING == schedulingState.getState() || WorkflowState.DRAINING == schedulingState.getState()) {
					
					List<WorkflowJob> workflowJobs = workflowRuns.get(runIdPair).getWorkflowJobs();
					List<WorkflowJob> operableJobs = workflowRuns.get(runIdPair).getOperableJobs();
					Map<UUID, JobState> unfinishedJobs = workflowRuns.get(runIdPair).getUnfinishedJobs();				
					Map<UUID, JobState> completedJobs = workflowRuns.get(runIdPair).getCompletedJobs();
					
					if (!operableJobs.isEmpty() && WorkflowState.DRAINING != schedulingState.getState()) {
						
						logger.info("the workflow has "
								+ workflowJobs.size() + " jobs, "
								+ operableJobs.size() + " can be started now, "
								+ completedJobs.size() + " are completed");
						// keep in RUNNING 
						runNextJobs(runIdPair, operableJobs);
						
					} else 	if (completedJobs.size() == workflowJobs.size()) {
						logger.info("workflow run " + runIdPair + " completed successfully");
						this.endWorkflowRun(runIdPair, WorkflowState.COMPLETED, null);
						
					} else if (operableJobs.isEmpty() && unfinishedJobs.isEmpty()) {
						
						// move to FAILED
						throw new WorkflowException("completed jobs didn't produce the files "
								+ "that are needed for the remaining tools, "
								+ completedJobs.size() + "/" 
								+ workflowJobs.size() + " jobs completed", WorkflowState.FAILED);
					}				
				}
			} catch (WorkflowException e) {
				
				logger.warn("workflow " + runIdPair + " failed: " + e.getMessage());
				schedulingState.setState(e.getState());
				schedulingState.setStateDetail(e.getMessage());
			}
			
			try {
				this.updateSessionDb(runIdPair);
				
			} catch (RestException e) {
				
				if (e.getResponse().getStatus() == 404) {
					logger.warn("the worklow " + runIdPair + " has been deleted");
					this.workflowRuns.remove(runIdPair);
				} else {
					logger.error("failed to update the WorkflowRun in sessions-db", e);
				}
			}
			
			if (schedulingState.getState().isFinished()) {
				logger.info("workflow run " + runIdPair + " is finished as " + schedulingState.getState() + " (" + schedulingState.getStateDetail() + ")");
				this.workflowRuns.remove(runIdPair);
			}
			
		}
	}
	
	private UUID runWorkflowJob(WorkflowRun run, WorkflowJob workflowJob) throws RestException {
		synchronized (workflowRuns) {
												
			Job job = new Job();
			
			// Convert WorkflowInputs to Inputs
			List<Input> jobInputs = workflowJob.getInputs().stream()
					.map(wi -> wi.deepCopy(new Input()))
					.collect(Collectors.toList());
			
			job.setCreatedBy(run.getCreatedBy());
			job.setInputs(jobInputs);
			job.setParameters(workflowJob.getParameters());
			job.setMetadataFiles(workflowJob.getMetadataFiles());
			job.setModule(workflowJob.getModule());
			job.setState(JobState.NEW);
			job.setToolCategory(workflowJob.getToolCategory());
			job.setToolId(workflowJob.getToolId());
			job.setToolName(workflowJob.getToolName());
			
			UUID jobId = sessionDbClient.createJob(run.getSessionId(), job);
						
			return jobId;
		}
	}
	
	private void updateInputBindings(WorkflowRunIdPair runIdPair, List<WorkflowJob> workflowJobs) throws RestException, BindException {
		synchronized (workflowRuns) {
			
			HashMap<UUID, Dataset> sessionDatasets = sessionDbClient.getDatasets(runIdPair.getSessionId());
			HashMap<UUID, Job> sessionJobs = sessionDbClient.getJobs(runIdPair.getSessionId());
			
			// this will update the local copy in this.workflowRuns, session-db will be updated later in updateWorkflowRun()
			WorkflowBinder.updateInputBindings(workflowJobs, sessionDatasets, sessionJobs);
		}
	}
	
	private void runNextJobs(WorkflowRunIdPair runIdPair, List<WorkflowJob> operableJobs) throws WorkflowException {
		synchronized (workflowRuns) {
			
			try {
				WorkflowRun run = sessionDbClient.getWorkflowRun(runIdPair.getSessionId(), runIdPair.getWorkflowRunId());
			
				for (WorkflowJob job : operableJobs) {
					
					UUID jobId = this.runWorkflowJob(run, job);
	
					//TODO store to workflowRuns explicitly
					// store the job id in memory to know when it's updated
					job.setJobId(jobId);
					
					// store the job state
					workflowRuns.get(run.getWorkflowRunIdPair()).putJobId(jobId, JobState.NEW);				
				}				
			} catch (RestException e) {
				// shouldn't happen when if all services are working
				logger.error("failed to create a new job", e);
				throw new WorkflowException("Internal error: failed to create a new job", WorkflowState.ERROR);
			}
		}
	}
	
	private void updateSessionDb(WorkflowRunIdPair runIdPair) throws RestException {
		synchronized (workflowRuns) {
			
			WorkflowRun sessionDbRun = sessionDbClient.getWorkflowRun(runIdPair.getSessionId(), runIdPair.getWorkflowRunId());
			
			WorkflowRun run = sessionDbRun.deepCopy();
			
			WorkflowSchedulingState schedulingState = this.workflowRuns.get(runIdPair);
			
			run.setWorkflowJobs(schedulingState.getWorkflowJobs());
			run.setState(schedulingState.getState());
			run.setStateDetail(schedulingState.getStateDetail());
			run.setEndTime(schedulingState.getTimestamp(schedulingState.getState()));
			
			// update only if there was any changes
			if (!run.equals(sessionDbRun)) {
				logger.info("update the workflow run in session-db " + sessionDbRun.getState() + " -> " + run.getState());
				// save updates to session-db for client			
				sessionDbClient.updateWorkflowRun(run.getSessionId(), run);
			} else {
				logger.debug("no changes to the workflow run in session-db");
			}
		}
	}
	
	private void handleJobFail(WorkflowRunIdPair runIdPair, WorkflowState state, String reason) throws RestException {
		synchronized (workflowRuns) {
			switch (onError) {
			case IGNORE:								
				logger.info("a job in workflow run " + runIdPair + " failed (" + reason + ") but the workflow run tries to continue");
				break;
			case BAIL_OUT:
				logger.info("a job in workflow run " + runIdPair + " failed (" + reason + "), cancel other jobs");
				cancelWorkflowRunJobs(runIdPair);
				// the workflow run should finish when we have received session-db (a finished state) event of each job  
				break;
			case DRAIN:
				logger.info("a job in workflow run " + runIdPair + " failed (" + reason + "), drain other jobs");
				WorkflowSchedulingState schedulingState = this.workflowRuns.get(runIdPair);
				schedulingState.setState(WorkflowState.DRAINING);
				schedulingState.setStateDetail(reason);
				break;
			default:
				logger.error("unrecognized job error mode: " + onError);
			}
		}
	}

	private void endWorkflowRun(WorkflowRunIdPair runIdPair, WorkflowState state, String reason) {
		synchronized (workflowRuns) {
						
			if (!state.isFinished()) {
				throw new IllegalArgumentException("cannot end the workflow run with state " + state);
			}
			
			Map<UUID, JobState> unfinishedJobs = this.workflowRuns.get(runIdPair).getUnfinishedJobs();
			if (!unfinishedJobs.isEmpty()) {
				throw new IllegalStateException("cannot end the workflow run " + runIdPair + " because it has " + unfinishedJobs.size() + " jobs running");
			}
			
			WorkflowSchedulingState schedulingState = workflowRuns.get(runIdPair);
			schedulingState.setState(state);
			schedulingState.setStateDetail(reason);				
		}
	}

	private void cancelWorkflowRunJobs(WorkflowRunIdPair runIdPair) {
		synchronized (workflowRuns) {
			
			logger.info("cancel jobs of workflow " + runIdPair);			
			WorkflowSchedulingState runSchedulingState = workflowRuns.get(runIdPair);
					
			if (runSchedulingState == null) {
				logger.warn("scheduling state not found for workflow " + runIdPair);
				return;
			}
			
			Map<UUID, JobState> unfinishedJobs = runSchedulingState.getUnfinishedJobs();
			
			if (unfinishedJobs.isEmpty()) {
				logger.info("no unfinished jobs");
			}
			
			// cancel all jobs in this workflow
			for (UUID jobId : unfinishedJobs.keySet()) {
				try {
					JobState jobState = runSchedulingState.getJobs().get(jobId);
					logger.info("cancel job " + jobId + " " + jobState);
					Job job = this.sessionDbClient.getJob(runIdPair.getSessionId(), jobId);
					if (!job.getState().isFinished()) {
						job.setState(JobState.CANCELLED);					
						job.setStateDetail("workflow cancelled");
						this.sessionDbClient.updateJob(runIdPair.getSessionId(), job);
													
						/* Cancel only once
						 * 
						 * Set the local job state to a finished state, so that we don't try to cancel
						 * repeatedly. Otherwise the WorkflowScheduler is only reacting to the state changes 
						 * that come from the
						 * events, so maybe it would be better store this information somewhere else. 
						 */
						runSchedulingState.putJobId(jobId, JobState.CANCELLED);
					}					
				} catch (RestException e) {					
					if (e.getResponse().getStatus() == 404) {
						logger.warn("tried to cancel job " + jobId + ", but it was already deleted");	
					}
					logger.error("cancelling job " + jobId + "failed", e);
				}
			}
		}
	}

	public Map<String, Object> getStatus() {
		HashMap<String, Object> status = new HashMap<>();
		status.put("newWorkflowCount", workflowRuns.get(WorkflowState.NEW).size());
		status.put("runningWorkflowCount", workflowRuns.get(WorkflowState.RUNNING).size());
		status.put("cancelWorkflowCount", workflowRuns.get(WorkflowState.CANCELLING).size());
		status.put("draininggWorkflowCount", workflowRuns.get(WorkflowState.DRAINING).size());
		return status;
	}
}
