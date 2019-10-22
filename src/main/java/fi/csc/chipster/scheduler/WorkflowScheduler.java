package fi.csc.chipster.scheduler;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.sessiondb.model.WorkflowInput;
import fi.csc.chipster.sessiondb.model.WorkflowJob;
import fi.csc.chipster.sessiondb.model.WorkflowRun;
import fi.csc.chipster.sessiondb.model.WorkflowRunIdPair;
import fi.csc.microarray.messaging.JobState;

public class WorkflowScheduler implements SessionEventListener, StatusSource {

	private Logger logger = LogManager.getLogger();
		
	private Config config;

	private SessionDbClient sessionDbClient;
	
	private long waitTimeout;
	private long timerInterval;
	private long waitNewSlotsPerUserTimeout;	

	private Timer timer;
	private SchedulerWorkflowRuns workflowRuns = new SchedulerWorkflowRuns();
	
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
    	    	
		//FIXME add separate configs for workflows
    	this.waitTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_TIMEOUT);
    	this.timerInterval = config.getLong(Config.KEY_SCHEDULER_JOB_TIMER_INTERVAL) * 1000;
    	this.waitNewSlotsPerUserTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_NEW_SLOTS_PER_USER_TIMEOUT);    	

    	this.sessionDbClient.subscribe(SessionDbTopicConfig.JOBS_TOPIC, this, "workflow-scheduler-job-listener");
    	this.sessionDbClient.subscribe(SessionDbTopicConfig.WORKFLOW_RUNS_TOPIC, this, "workflow-scheduler-workflow-listener");
    	    	    
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
	 * The unfinished workflows in the db are always in NEW or RUNNING state.   
	 * 
	 * @throws RestException
	 */
	private void getStateFromDb() throws RestException {
		synchronized (workflowRuns) {			
			
			List<WorkflowRunIdPair> newDbWorkflows = sessionDbClient.getWorkflowRuns(JobState.NEW);
			if (!newDbWorkflows.isEmpty()) {
				logger.info("found " + newDbWorkflows.size() + " waiting workflows from the session-db");				
				for (WorkflowRunIdPair runIdPair : newDbWorkflows) {
					addWorkflowRun(runIdPair);
				}
			}
			
			List<WorkflowRunIdPair> runningDbWorkflows = sessionDbClient.getWorkflowRuns(JobState.RUNNING);
			if (!runningDbWorkflows.isEmpty()) {
				logger.info("found " + runningDbWorkflows.size() + " running workflows from the session-db");
				for (WorkflowRunIdPair runIdPair : runningDbWorkflows) {
					addWorkflowRun(runIdPair);;
				}
			}			
		}
	}

	public void close() {
	}

	@Override
	public void onEvent(SessionEvent e) {
		logger.debug("received a workflow event: " + e.getResourceType() + " " + e.getType());
		try {			
			if (e.getResourceType() == ResourceType.WORKFLOW_RUN) {
				handleWorkflowEvent(e, new WorkflowRunIdPair(e.getSessionId(), e.getResourceId()));
			}
			if (e.getResourceType() == ResourceType.JOB) {
				handleJobEvent(e, new IdPair(e.getSessionId(), e.getResourceId()));
			}
		} catch (Exception ex) {
			logger.error("error when handling a session event", ex);
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
				logger.info("received a new workflow run " + runIdPair + ", trying to schedule it");
				addWorkflowRun(runIdPair);
				
				break;
			default:
				break;
			}
			break;

		case UPDATE:
			run = sessionDbClient.getWorkflowRun(e.getSessionId(), e.getResourceId());
			if (run.getState() == JobState.CANCELLED) {
				cancelWorkflowRun(runIdPair);
			}
			break;
		case DELETE:
			cancelWorkflowRun(runIdPair);				
			break;
		default:
			break;
		}
	}
	
	private void addWorkflowRun(WorkflowRunIdPair runIdPair) throws RestException {
		synchronized (workflowRuns) {
			WorkflowRun run = this.sessionDbClient.getWorkflowRun(runIdPair.getSessionId(), runIdPair.getWorkflowRunId());
			
			WorkflowSchedulingState schedulingState = new WorkflowSchedulingState(run.getCreatedBy());
			schedulingState.setNewTimestamp();
			
			for (WorkflowJob workflowJob : run.getWorkflowJobs()) {
				if (workflowJob.getJobId() != null) {
					schedulingState.putJobId(workflowJob.getJobId());
				}
			}
			workflowRuns.put(runIdPair, schedulingState);
			this.runNextJobs(runIdPair);
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

				if (jobState.isFinished()) {
					WorkflowRunIdPair runIdPair = this.workflowRuns.getWorkflowRunId(jobIdPair);
					
					if (runIdPair == null) {
						// not a workflow job
					} else if (JobState.COMPLETED == jobState) {
						runNextJobs(runIdPair);
					} else {
						// job was unsuccesful
						endWorkflowRun(runIdPair, jobState, "job state " + jobState);
					}
				}
				break;
				
			case DELETE:
				WorkflowRunIdPair runIdPair = this.workflowRuns.getWorkflowRunId(jobIdPair);
				
				if (runIdPair != null) {
					endWorkflowRun(runIdPair, JobState.CANCELLED, "job deleted");
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
			
			for (WorkflowRunIdPair runIdPair : workflowRuns.getRunning()) {
				WorkflowSchedulingState schedulingState = workflowRuns.get(runIdPair);
				
				if (schedulingState.isUserLimitReached()) {
					if (schedulingState.getTimeSinceNew() > waitNewSlotsPerUserTimeout) {
						expire(runIdPair, "The workflow couldn't run, because you had the maximum number of workflows running. Please try again after you other workflow have completed.");
					}
				} else {
					if (schedulingState.getTimeSinceNew() > waitTimeout) {						
						expire(runIdPair, "the workflow max time reached (" + waitTimeout + " seconds)");
					}
				}
			}
		}
	}
	
	private boolean updateInputBindings(WorkflowRun run, HashMap<UUID, Dataset> sessionDatasets, HashMap<UUID, Job> sessionJobs) throws RestException, BindException {
			
		boolean isChanged = false;
		
		for (WorkflowJob workflowJob : run.getWorkflowJobs()) {
			
			logger.info("job " + workflowJob.getToolId() + " has " + workflowJob.getInputs().size() + " input(s)");
			
			for (WorkflowInput input : workflowJob.getInputs()) {
				
				logger.info("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "': check binding");
				
				if (updateBinding(input, run, workflowJob, sessionDatasets, sessionJobs)) {
					isChanged = true;
				}
			}
		}
		
		return isChanged;
	}
		
	private boolean updateBinding(WorkflowInput input, WorkflowRun run, WorkflowJob workflowJob, HashMap<UUID, Dataset> sessionDatasets,
			HashMap<UUID, Job> sessionJobs) throws BindException {
				
		Dataset sourceDataset = this.getSourceDataset(input, sessionDatasets);
		WorkflowJob sourceWorkflowJob = this.getSourceWorkflowJob(input, run);
		Job sourceJob = this.getSourceJob(input, sessionJobs, run);
		
		if (sourceDataset != null) {
			
			// input is already bound to dataset
			logger.info("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "':  bound to dataset " + input.getDatasetId());
		
		} else if (sourceWorkflowJob != null && sourceJob == null) {
			logger.info("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "': waiting for workflow job " + input.getSourceWorkflowJobId() + " to start");
			
		} else if (sourceWorkflowJob != null && sourceJob != null) { 	
																
			logger.info("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "': is bound to job " + sourceWorkflowJob.getJobId() + " output '" + input.getSourceJobOutputId() + "'");
						
			if (sourceJob.getState() == JobState.COMPLETED) {
				
				Dataset dataset = getSourceDataset(input, sourceJob, sessionDatasets);
				
				logger.info("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "': bind dataset " + dataset.getName());
				input.setDatasetId(dataset.getDatasetId().toString());
				input.setDisplayName(dataset.getName());
				
				return true;
				
			} else {
				logger.info("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "': waiting for job " + sourceJob.getToolId() + " (" + sourceJob.getState() + ")");
			}
		} else {
			throw new BindException("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "': is not bound to anything");			
		}
		
		return false;
	}
	

	private Dataset getSourceDataset(WorkflowInput input, Job sourceJob, HashMap<UUID, Dataset> sessionDatasets) throws BindException {
		
		List<Dataset> sourceDatasets = sessionDatasets.values().stream()
				.filter(d -> sourceJob.getJobId().equals(d.getSourceJob()))
				.filter(d -> input.getSourceJobOutputId().equals(d.getSourceJobOutputId()))
				.collect(Collectors.toList());
		
		if (sourceDatasets.isEmpty()) {
			throw new BindException("the job " + sourceJob.getToolId() + " has completed, but its output '" + input.getSourceJobOutputId() + "' dataset cannot be found");
		} else if (sourceDatasets.size() > 1) {
			throw new BindException("the job " + sourceJob.getToolId() + " has more than one datasets for output '" + input.getSourceJobOutputId() + "'");
		} else {
			// the job output is ready, we can bind the dataset
			return sourceDatasets.get(0);
		}
	}
	
	private Job getSourceJob(WorkflowInput input, HashMap<UUID, Job> sessionJobs, WorkflowRun run) throws BindException {
		WorkflowJob sourceWorkflowJob = getSourceWorkflowJob(input, run);
		
		if (sourceWorkflowJob != null && sourceWorkflowJob.getJobId() != null) {
			
			Job sourceJob = sessionJobs.get(sourceWorkflowJob.getJobId());
			
			if (sourceJob == null) {
				throw new BindException("job " + sourceWorkflowJob.getJobId() + " is not found from the session");
			}
			return sourceJob;
		}
		return null;
	}

	private Dataset getSourceDataset(WorkflowInput input, HashMap<UUID, Dataset> sessionDatasets) throws BindException {
		
		if (input.getDatasetId() != null) {
			Dataset dataset = sessionDatasets.get(UUID.fromString(input.getDatasetId()));
			
			if (dataset == null) {
				throw new BindException(" input '" + input.getInputId() + "' is bound to non existing dataset " + input.getDatasetId());
			} else {
				return dataset;
			}
		}
		return null;
	}
	
	private WorkflowJob getSourceWorkflowJob(WorkflowInput input, WorkflowRun run) throws BindException {
		
		if (input.getSourceWorkflowJobId() != null) {
			Optional<WorkflowJob> sourceWorkflowJobOptional = run.getWorkflowJobs().stream()
					.filter(j -> UUID.fromString(input.getSourceWorkflowJobId()).equals(j.getWorkflowJobId()))
					.findFirst();
		
			if (sourceWorkflowJobOptional.isEmpty()) {
				throw new BindException("input '" + input.getInputId() + "': waiting workflow job " + input.getSourceWorkflowJobId() + " which doesn't exist");
			}
			
			return sourceWorkflowJobOptional.get();
		}
		return null;
	}
	
	private List<Job> getJobs(WorkflowRun run, HashMap<UUID, Job> sessionJobs) throws RestException {
		
		return run.getWorkflowJobs().stream()
			.filter(workflowJob -> workflowJob.getJobId() != null)
			.map(workflowJob -> sessionJobs.get(workflowJob.getJobId()))
			.filter(job -> job != null)
			.collect(Collectors.toList());
	}
	
	private List<WorkflowJob> getUnstartedJobs(WorkflowRun run) throws RestException {
		
		return run.getWorkflowJobs().stream()
			.filter(workflowJob -> workflowJob.getJobId() == null)
			.collect(Collectors.toList());
	}
	
	/**
	 * Get jobs that haven't started yet but are ready to run
	 * 
	 * @param run
	 * @param schedulingState
	 * @return
	 * @throws RestException
	 */
	private List<WorkflowJob> getOperableJobs(WorkflowRun run) throws RestException {
					
		List<WorkflowJob> operableJobs = this.getUnstartedJobs(run).stream()
			.filter(workflowJob -> {
			
			List<WorkflowInput> readyInputs = workflowJob.getInputs().stream()
				.filter(input -> input.getDatasetId() != null)
				.collect(Collectors.toList());
			
			// check if all inputs are ready in this job
			return readyInputs.size() == workflowJob.getInputs().size();
			
		}).collect(Collectors.toList());
		
		return operableJobs;
	}
	
	private void runWorkflowJob(WorkflowRun run, WorkflowJob workflowJob) throws RestException {
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
						
			// store the job id in memory to know when it's updated
			workflowRuns.get(run.getWorkflowRunIdPair()).putJobId(jobId);					
			
			// store the job in session-db for client to show progress
			workflowJob.setJobId(jobId);
		}
	}
	
	private void runNextJobs(WorkflowRunIdPair runIdPair) throws RestException {
		synchronized (workflowRuns) {
			
			WorkflowRun run = sessionDbClient.getWorkflowRun(runIdPair.getSessionId(), runIdPair.getWorkflowRunId());
			
			HashMap<UUID, Dataset> sessionDatasets = sessionDbClient.getDatasets(runIdPair.getSessionId());
			HashMap<UUID, Job> sessionJobs = sessionDbClient.getJobs(runIdPair.getSessionId());
						
			boolean isChanged;
			try {
				isChanged = this.updateInputBindings(run, sessionDatasets, sessionJobs);
						
				List<WorkflowJob> operableJobs = this.getOperableJobs(run);
				List<Job> unfinishedJobs = getUnfinishedJobs(run, sessionJobs);				
				List<Job> completedJobs = getCompletedJobs(run, sessionJobs);
				
				
				logger.info("the workflow has "
					+ run.getWorkflowJobs().size() + " jobs, "
					+ operableJobs.size() + " can be started now, "
					+ completedJobs.size() + " are completed");
				
				for (WorkflowJob job : operableJobs) {
					// adds jobIds the workflowJob
					this.runWorkflowJob(run, job);
					isChanged = true;
				}
				
				if (run.getState() == JobState.NEW) {
					run.setState(JobState.RUNNING);
					isChanged = true;
				}
				
				if (completedJobs.size() == run.getWorkflowJobs().size()) {
					run.setState(JobState.COMPLETED);
					isChanged = true;
				} else if (operableJobs.isEmpty() && unfinishedJobs.isEmpty()) {
					run.setState(JobState.FAILED);
					run.setStateDetail("completed jobs didn't produce the files that are needed for the remaining tools, "
							+ completedJobs.size() + "/" + run.getWorkflowJobs().size() + " jobs completed");
					isChanged = true;
				}
					
				
				if (isChanged) { 
					// save updates to session-db for client  
					sessionDbClient.updateWorkflowRun(run.getSessionId(), run);
				}
			} catch (BindException e) {
				this.expire(runIdPair, e.getMessage());
			}
		}
	}

	private List<Job> getCompletedJobs(WorkflowRun run, HashMap<UUID, Job> sessionJobs) throws RestException {
		return this.getJobs(run, sessionJobs).stream()
		.filter(job -> job.getState() == JobState.COMPLETED)
		.collect(Collectors.toList());
	}

	private List<Job> getUnfinishedJobs(WorkflowRun run, HashMap<UUID, Job> sessionJobs) throws RestException {
		return this.getJobs(run, sessionJobs).stream()
		.filter(job -> !job.getState().isFinished())
		.collect(Collectors.toList());
	}

	private void expire(WorkflowRunIdPair runIdPair, String reason) {
		logger.warn("workflow " + runIdPair + " expired: " + reason);
		endWorkflowRun(runIdPair, JobState.EXPIRED_WAITING, reason);
	}
	
	private void endWorkflowRun(WorkflowRunIdPair runIdPair, JobState state, String reason) {
		synchronized (workflowRuns) {			
			workflowRuns.remove(runIdPair);
			
			try {			
				WorkflowRun run = sessionDbClient.getWorkflowRun(runIdPair.getSessionId(), runIdPair.getWorkflowRunId());
				run.setEndTime(Instant.now());
				run.setState(state);
				run.setStateDetail(reason);
				sessionDbClient.updateWorkflowRun(runIdPair.getSessionId(), run);
			} catch (RestException e) {
				logger.error("could not set a workflow " + runIdPair + " to " + state, e);
			}
		}
	}

	private void cancelWorkflowRun(WorkflowRunIdPair runIdPair) throws RestException {
		synchronized (workflowRuns) {
			
			logger.info("cancel workflow " + runIdPair);			
			WorkflowSchedulingState runSchedulingState = workflowRuns.remove(runIdPair);
					
			if (runSchedulingState == null) {
				logger.warn("scheduling state not found for workflow " + runIdPair);
				return;
			}
			
			// cancel all jobs in this workflow
			for (UUID jobId : runSchedulingState.getJobIds()) {
				Job job = this.sessionDbClient.getJob(runIdPair.getSessionId(), jobId);
				job.setState(JobState.CANCELLED);
				job.setStateDetail("workflow cancelled");
				this.sessionDbClient.updateJob(runIdPair.getSessionId(), job);
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
