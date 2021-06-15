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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
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
import jakarta.servlet.ServletException;
import jakarta.websocket.DeploymentException;
import jakarta.ws.rs.ProcessingException;

public class Scheduler implements SessionEventListener, StatusSource, JobSchedulerCallback {

	private Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private String serviceId;
	private Config config;

	private AuthenticationClient authService;
	private ServiceLocatorClient serviceLocator;
	private SessionDbClient sessionDbClient;
	private ToolboxClientComp toolbox;

	private long jobTimerInterval;
	private int maxScheduledAndRunningSlotsPerUser;
	private int maxNewSlotsPerUser;
	private long waitRunnableTimeout;

	private Timer jobTimer;
	private SchedulerJobs jobs = new SchedulerJobs();
	private HttpServer adminServer;

	@SuppressWarnings("unused")
	private BashJobScheduler bashJobScheduler;
	private OfferJobScheduler offerJobScheduler;


	public Scheduler(Config config) {
		this.config = config;
	}

	public void startServer()
			throws ServletException, DeploymentException, InterruptedException, RestException, IOException {

		String username = Role.SCHEDULER;
		String password = config.getPassword(username);

		this.waitRunnableTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_RUNNABLE_TIMEOUT);
		this.jobTimerInterval = config.getLong(Config.KEY_SCHEDULER_JOB_TIMER_INTERVAL) * 1000;
		this.maxScheduledAndRunningSlotsPerUser = config
				.getInt(Config.KEY_SCHEDULER_MAX_SCHEDULED_AND_RUNNING_SLOTS_PER_USER);
		this.maxNewSlotsPerUser = config.getInt(Config.KEY_SCHEDULER_MAX_NEW_SLOTS_PER_USER);

		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());
		String toolboxUrl = this.serviceLocator.getInternalService(Role.TOOLBOX).getUri();
		this.toolbox = new ToolboxClientComp(toolboxUrl);
		
		this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.SERVER);
		this.sessionDbClient.subscribe(SessionDbTopicConfig.JOBS_TOPIC, this, "scheduler-job-listener");
		
		this.bashJobScheduler = new BashJobScheduler(this, config);
		this.offerJobScheduler = new OfferJobScheduler(config, authService, this);
		
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

		this.adminServer = RestUtils.startAdminServer(new AdminResource(this, offerJobScheduler.getPubSubServer()), null, Role.SCHEDULER,
				config, authService, this.serviceLocator);

		logger.info("scheduler is up and running");
	}
	
	private JobScheduler getJobScheduler(UUID sessionId, UUID jobId) {
		return bashJobScheduler;
//		return offerJobScheduler;
	}

	/**
	 * Get unfinished jobs from the session-db
	 * 
	 * The unfinished jobs in the db are always in NEW or RUNNING state. The
	 * SCHEDULED state isn't updated to the db, because changing state so fast would
	 * cause only more harm.
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
						jobs.addNewJob(new IdPair(idPair.getSessionId(), idPair.getJobId()), job.getCreatedBy(),
								getSlots(job));
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
						jobs.addRunningJob(new IdPair(idPair.getSessionId(), idPair.getJobId()), job.getCreatedBy(),
								getSlots(job));
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
				logger.info("tried to get the slot count of tool " + job.getToolId()
						+ " from toolbox but the tool was not found, default to 1");
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
	 * 
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
		
		if (this.offerJobScheduler != null) {
			this.offerJobScheduler.close();
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
					logger.error("received a CREATE event of job " + asShort(e.getResourceId())
							+ ", but couldn't get it from session-db", e);
					break;
				}
				switch (job.getState()) {
				case NEW:


					// when a client adds a new job, try to schedule it immediately
					logger.info("received a new job " + jobIdPair + ", trying to schedule it");
					SchedulerJob jobState = jobs.addNewJob(jobIdPair, job.getCreatedBy(), getSlots(job));
					schedule(jobIdPair, jobState);

					break;
				default:
					break;
				}
				break;

			case UPDATE:
				job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
				
				// when the comp has finished the job, we can forget it
				if (job.getState().isFinishedByComp()) {
					logger.info("job " + jobIdPair + " finished by comp");
					
					jobs.remove(jobIdPair);
					
					this.getJobScheduler(jobIdPair.getSessionId(), jobIdPair.getJobId())
						.removeFinishedJob(jobIdPair.getSessionId(), jobIdPair.getJobId());
				}

				// job has been cancelled, inform comps and remove from scheduler
				else if (job.getState() == JobState.CANCELLED) {
					cancelJob(jobIdPair);
				} else {
									
					SchedulerJob schedulerJob = jobs.get(jobIdPair);
					
					// comp is updating the job, so it's alive
					schedulerJob.setRunningTimestamp();
				}
				break;
			case DELETE:
				cancelJob(jobIdPair);
				break;
			default:
				break;
			}
		}
	}	

	private void cancelJob(IdPair jobIdPair) {
		
		synchronized (jobs) {

			logger.info("cancel job " + jobIdPair);
			jobs.remove(jobIdPair);
		}
		
		this.getJobScheduler(jobIdPair.getSessionId(), jobIdPair.getJobId())
			.cancelJob(jobIdPair.getSessionId(), jobIdPair.getJobId());
	}

	/**
	 * Timer for checking the timeouts
	 */
	private void handleJobTimer() {
		synchronized (jobs) {
			
			// check max queuing time

			for (IdPair jobIdPair : jobs.getNewJobs().keySet()) {
				SchedulerJob jobState = jobs.get(jobIdPair);
				
				if (jobState.getTimeSinceNew() > waitRunnableTimeout) {
					
					// server full
					jobs.remove(jobIdPair);
					expire(jobIdPair,
							"There was no computing resources available to run this job, please try again later");
				}
			}
		}
	}

	private void schedule(IdPair jobIdPair, SchedulerJob jobState) {
		synchronized (jobs) {

			int userNewCount = jobs.getNewSlots(jobState.getUserId());
			int userScheduledCount = jobs.getScheduledSlots(jobState.getUserId());
			int userRunningCount = jobs.getRunningSlots(jobState.getUserId());
			
			// check if user is allowed to run jobs of this size
			if (jobState.getSlots() > this.maxScheduledAndRunningSlotsPerUser) {
				logger.info("user " + jobState.getUserId() + " doesn't have enough slots for the tool");
				IdPair idPair = new IdPair(jobIdPair.getSessionId(), jobIdPair.getJobId());
				this.jobs.remove(idPair);
				this.endJob(idPair, JobState.ERROR,
						"Not enough job slots. The tool requires " + jobState.getSlots()
								+ " slots but your user account can have only "
								+ this.maxScheduledAndRunningSlotsPerUser + " slots running.");
				return;
			}
			
			// keep in queue if normal personal limit of running jobs is reached
			if (userRunningCount + userScheduledCount + jobState.getSlots() > this.maxScheduledAndRunningSlotsPerUser) {
				logger.info("max job count of running jobs (" + this.maxScheduledAndRunningSlotsPerUser
						+ ") reached by user " + jobState.getUserId());
				// user's slot quota is full
				// keep the job in queue and try again later
				return;
			}

			// fail the job immediately if user has created thousands of new jobs
			if (userNewCount + jobState.getSlots() > this.maxNewSlotsPerUser) {
				logger.info("max job count of new jobs (" + this.maxNewSlotsPerUser + ") reached by user "
						+ jobState.getUserId());
				IdPair idPair = new IdPair(jobIdPair.getSessionId(), jobIdPair.getJobId());
				this.jobs.remove(idPair);
				this.endJob(idPair, JobState.ERROR,
						"Maximum number of new jobs per user (" + this.maxNewSlotsPerUser + ") reached.");
				return;
			}

			// schedule
			
			// set the schedule timestamp to be able to calculate user's slot quota when many jobs are started at the same time
			jobState.setScheduleTimestamp();
			
			this.updateJob(jobIdPair, JobState.SCHEDULED, "");

			this.getJobScheduler(jobIdPair.getSessionId(), jobIdPair.getJobId())
				.scheduleJob(jobIdPair.getSessionId(), jobIdPair.getJobId());
		}
	}

	

	/**
	 * Move from NEW to EXPIRED_WAITING
	 * 
	 * @param jobId
	 * @param reason
	 */
	public void expire(IdPair jobId, String reason) {
		logger.warn("max wait time reached for job " + jobId);
		endJob(jobId, JobState.EXPIRED_WAITING, reason);
	}

	/**
	 * Set dbState in the session-db
	 * 
	 * This should be used only when anything else isn't updating the job, e.g. NEW
	 * or EXPIRED_WAITING jobs. Job's end time is set to current time.
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
	 * Set dbState in the session-db
	 * 
	 * This should be used only when anything else isn't updating the job, e.g. NEW
	 * or EXPIRED_WAITING jobs.
	 * 
	 * @param jobId
	 * @param reason
	 */
	private void updateJob(IdPair jobId, JobState jobState, String reason) {
		try {
			Job job = sessionDbClient.getJob(jobId.getSessionId(), jobId.getJobId());
			job.setState(jobState);
			job.setStateDetail("Job state " + jobState + " (" + reason + ")");
			sessionDbClient.updateJob(jobId.getSessionId(), job);
		} catch (RestException e) {
			logger.error("could not set an old job " + jobId + " to " + jobState, e);
		}
	}

	/**
	 * First 4 letters of the UUID for log messages
	 * 
	 * @param id
	 * @return
	 */
	public static String asShort(UUID id) {
		return id.toString().substring(0, 4);
	}

	public Map<String, Object> getStatus() {
		HashMap<String, Object> status = new HashMap<>();
		status.put("newJobCount", jobs.getNewJobs().size());
		status.put("runningJobCount", jobs.getRunningJobs().size());
				
		status.put("newSlotCount", SchedulerJobs.getSlots(jobs.getNewJobs().values()));
		status.put("runningSlotCount", SchedulerJobs.getSlots(jobs.getRunningJobs().values()));
		
		status.putAll(this.offerJobScheduler.getStatus());
		
		return status;
	}

	/**
	 * Get all waiting jobs and try to schedule them
	 * 
	 * We have to send all of them, because we can't know what kind of jobs are
	 * accepted by different comps.
	 * 
	 * The oldest jobs jobs are sent first although the previous failed attempts
	 * have most likely already reseted the timestamp.
	 * 
	 * @throws RestException
	 */
	@Override
	public void newResourcesAvailable() {
		synchronized (jobs) {
			
			List<IdPair> newJobs = jobs.getNewJobs().entrySet().stream()
					.sorted((e1, e2) -> e1.getValue().getNewTimestamp().compareTo(e2.getValue().getNewTimestamp()))
					.map(e -> e.getKey()).collect(Collectors.toList());
	
			if (newJobs.size() > 0) {
				logger.info("rescheduling " + newJobs.size() + " waiting jobs");
				for (IdPair idPair : newJobs) {
					SchedulerJob jobState = jobs.get(idPair);
					schedule(idPair, jobState);
				}
			}
		}
	}
}
