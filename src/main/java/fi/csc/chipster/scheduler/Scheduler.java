package fi.csc.chipster.scheduler;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
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
import fi.csc.chipster.scheduler.bash.BashJobScheduler;
import fi.csc.chipster.scheduler.offer.OfferJobScheduler;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.EventType;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;
import fi.csc.chipster.toolbox.ToolboxClientComp;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.chipster.toolbox.runtime.Runtime;
import jakarta.servlet.ServletException;
import jakarta.websocket.DeploymentException;

public class Scheduler implements SessionEventListener, StatusSource, JobSchedulerCallback {


	private Logger logger = LogManager.getLogger();
	
	private static final String CONF_SCHEDULER_BASH_ENABLED = "scheduler-bash-enabled";
	private static final String CONF_GET_JOBS_FROM_DB = "scheduler-get-jobs-from-db";

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

	private BashJobScheduler bashJobScheduler;
	private OfferJobScheduler offerJobScheduler;

	private long heartbeatLostTimeout;

	private long waitTimeout;

	private boolean getJobsFromDb;

	private boolean bashJobSchedulerEnabled;


	public Scheduler(Config config) {
		this.config = config;
	}

	public void startServer()
			throws ServletException, DeploymentException, InterruptedException, RestException, IOException {

		String username = Role.SCHEDULER;
		String password = config.getPassword(username);

		this.waitRunnableTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_RUNNABLE_TIMEOUT);
		this.jobTimerInterval = config.getLong(Config.KEY_SCHEDULER_JOB_TIMER_INTERVAL) * 1000;
		this.heartbeatLostTimeout = config.getLong(Config.KEY_SCHEDULER_HEARTBEAT_LOST_TIMEOUT);
		this.maxScheduledAndRunningSlotsPerUser = config
				.getInt(Config.KEY_SCHEDULER_MAX_SCHEDULED_AND_RUNNING_SLOTS_PER_USER);
		this.maxNewSlotsPerUser = config.getInt(Config.KEY_SCHEDULER_MAX_NEW_SLOTS_PER_USER);
		this.waitTimeout = config.getLong(Config.KEY_SCHEDULER_WAIT_TIMEOUT);
		this.getJobsFromDb = config.getBoolean(CONF_GET_JOBS_FROM_DB);
		this.bashJobSchedulerEnabled = config.getBoolean(CONF_SCHEDULER_BASH_ENABLED);
		
		logger.info("runnable jobs can wait " + waitRunnableTimeout + " seconds in queue");
		logger.info("check jobs every " + jobTimerInterval/1000 + " second(s)");
		logger.info("remove job if heartbeat is lost for " + heartbeatLostTimeout + " seconds");
		logger.info("max scheduled and running slots: " + maxScheduledAndRunningSlotsPerUser);
		logger.info("max slots in queue per user: " + maxNewSlotsPerUser);
		logger.info("job can be rescheduled after: " + waitTimeout + " seconds");

		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());
		String toolboxUrl = this.serviceLocator.getInternalService(Role.TOOLBOX).getUri();
		this.toolbox = new ToolboxClientComp(toolboxUrl);
		
		this.sessionDbClient = new SessionDbClient(serviceLocator, authService.getCredentials(), Role.SERVER);
		this.sessionDbClient.subscribe(SessionDbTopicConfig.JOBS_TOPIC, this, "scheduler-job-listener");
		
		logger.info("start " + BashJobScheduler.class.getSimpleName());
		this.bashJobScheduler = new BashJobScheduler(this, this.sessionDbClient, this.serviceLocator, config);
		logger.info("start " + OfferJobScheduler.class.getSimpleName());
		this.offerJobScheduler = new OfferJobScheduler(config, authService, this);
		
		if (this.getJobsFromDb) {
			logger.info("getting unfinished jobs from the session-db");
			getStateFromDb();
		} else {
			logger.info("getting unfinished jobs from the session-db is disabled");
		}
			
		
		// start checking job timeouts only after all running jobs have had a chance to
		// report their heartbeat (when scheduler is restarted)
		long jobTimerStartDelay = Math.max(
				this.jobTimerInterval, 
				Math.max(this.bashJobScheduler.getHeartbeatInterval(), 
						this.offerJobScheduler.getHeartbeatInterval())) + 1000;

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
		}, jobTimerStartDelay, jobTimerInterval);

		logger.info("starting the admin rest server");

		this.adminServer = RestUtils.startAdminServer(new AdminResource(this, offerJobScheduler.getPubSubServer()), null, Role.SCHEDULER,
				config, authService, this.serviceLocator);

		logger.info("scheduler is up and running");
	}
	
	private JobScheduler getJobScheduler(SchedulerJob job) {
		if (this.bashJobSchedulerEnabled) {
			return bashJobScheduler;
		} else {
			return offerJobScheduler;
		}
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
						ToolboxTool tool = this.toolbox.getTool(job.getToolId());
						
						if (tool == null) {
							logger.warn("cannot schedule job " + idPair + ", tool not found: " + job.getToolId());
							this.expire(idPair, "tool not found", null);
							continue;
						}
						
						Runtime runtime = this.toolbox.getRuntime(tool.getRuntime());
						
						if (runtime == null) {
							logger.warn("cannot schedule job " + idPair + ", runtime not found: " + tool.getRuntime());
							this.expire(idPair, "runtime not found", null);
							continue;
						}

						SchedulerJob schedulerJob = jobs.addNewJob(
								new IdPair(idPair.getSessionId(), idPair.getJobId()), 
								job.getCreatedBy(),
								getSlots(job, tool), 
								tool,
								runtime);
						schedule(idPair, schedulerJob);
					} catch (RestException | IOException e) {
						logger.error("could not add a new job " + asShort(idPair.getJobId()), e);
					}
				}
			}

			List<IdPair> runningDbJobs = sessionDbClient.getJobs(JobState.RUNNING);
			if (!runningDbJobs.isEmpty()) {
				logger.info("found " + runningDbJobs.size() + " running jobs from the session-db");
				for (IdPair idPair : runningDbJobs) {
					try {
						Job job = sessionDbClient.getJob(idPair.getSessionId(), idPair.getJobId());
						ToolboxTool tool = this.toolbox.getTool(job.getToolId());
						
						if (tool == null) {
							logger.warn("cannot schedule job " + idPair + ", tool not found: " + job.getToolId());
							this.expire(idPair, "tool not found", null);
							continue;
						}
						
						Runtime runtime = this.toolbox.getRuntime(tool.getRuntime());
						
						if (runtime == null) {
							logger.warn("cannot schedule job " + idPair + ", runtime not found: " + tool.getRuntime());
							this.expire(idPair, "runtime not found", null);
							continue;
						}
						
						int slots = getSlots(job, tool);
						SchedulerJob schedulerJob = jobs.addRunningJob(new IdPair(idPair.getSessionId(), idPair.getJobId()), job.getCreatedBy(),
								slots, tool, runtime);
						this.getJobScheduler(schedulerJob).addRunningJob(idPair, slots, tool);
					} catch (RestException | IOException e) {
						logger.error("could add a running job " + asShort(idPair.getJobId()), e);
					}
				}
			}
		}
	}

	private int getSlots(Job job, ToolboxTool tool) {

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
		
		// get the job and tool before taking the lock
		Job job = null;
		ToolboxTool tool = null;
		Runtime runtime = null;
		
		if (EventType.CREATE == e.getType() || EventType.UPDATE == e.getType()) {
			try {
				job = sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
			} catch (RestException err) {
				logger.error("received a " + e.getType() + " event of job " + asShort(e.getResourceId())
				+ ", but couldn't get it from session-db", e);
				return;
			}
		}
		
		if (EventType.CREATE == e.getType()) {
			try {
				tool = this.toolbox.getTool(job.getToolId());
			} catch (IOException e1) {
				logger.error("cannot schedule new job " + jobIdPair + ": failed to get the tool from toolbox", e1);
				return;
			}
			
			try {
				runtime = this.toolbox.getRuntime(tool.getRuntime());
				
			} catch (RestException e1) {
				logger.error("cannot schedule new job " + jobIdPair + ": failed to get the runtime from toolbox", e);
				return;
			}
			
			if (runtime == null) {
				logger.error("cannot schedule new job " + jobIdPair + ", runtime not found: " + tool.getRuntime());
				return;
			}
		}
		
		synchronized (jobs) {
			switch (e.getType()) {
			case CREATE:
				switch (job.getState()) {
				case NEW:


					// when a client adds a new job, try to schedule it immediately
					logger.info("received a new job " + jobIdPair + ", trying to schedule it");
					SchedulerJob jobState = jobs.addNewJob(jobIdPair, job.getCreatedBy(), getSlots(job, tool), tool, runtime);
					schedule(jobIdPair, jobState);

					break;
				default:
					break;
				}
				break;

			case UPDATE:
				
				// when the comp has finished the job, we can forget it
				if (job.getState().isFinishedByComp()) {
					logger.info("job " + jobIdPair + " finished by comp");
					
					SchedulerJob removedJob = jobs.remove(jobIdPair);
					
					if (removedJob != null) {
						JobScheduler jobScheduler = this.getJobScheduler(removedJob);
						jobScheduler.removeFinishedJob(jobIdPair);
						
						newResourcesAvailable(jobScheduler);
					} else {
						logger.error("job not found");
					}
				}

				// job has been cancelled, inform comps and remove from scheduler
				else if (job.getState() == JobState.CANCELLED) {
					cancelJob(jobIdPair);
					
				} else if (job.getState() == JobState.EXPIRED_WAITING) {
					logger.info("received event, job " + jobIdPair + " was set to " + JobState.EXPIRED_WAITING);
					
				} else {
									
					SchedulerJob schedulerJob = jobs.get(jobIdPair);
					
					if (schedulerJob == null) {
						
						logger.warn("job " + jobIdPair + " was updated, but scheduler doesn't know about it (is there multiple schedulers?");
					}
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
		
		SchedulerJob removedJob = null;
		
		synchronized (jobs) {

			logger.info("cancel job " + jobIdPair);
			removedJob = jobs.remove(jobIdPair);
		}
		
		if (removedJob != null) {
			this.getJobScheduler(removedJob)
				.cancelJob(jobIdPair);
		}
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
					expire(jobIdPair,
							"There was no computing resources available to run this job, please try again later", null);
				}
			}
			
			// check if scheduled job is actually running already

			for (IdPair jobIdPair : jobs.getScheduledJobs().keySet()) {
				JobScheduler jobScheduler = this.getJobScheduler(jobs.get(jobIdPair));
				Instant lastHeartbeat = jobScheduler.getLastHeartbeat(jobIdPair);
				
				if (lastHeartbeat == null) {
					
					// not running yet					
					
					if (jobs.get(jobIdPair).getTimeSinceScheduled() > this.waitTimeout * 5) {
						// JobSchedule should have call busy() already
						logger.warn("JobScheduler has kept " + jobIdPair + " in scheduled state too long, timeout");
						this.busy(jobIdPair);
					}
					
				} else {
					
					// running in scheduler, comp will change the state in db and client later
					logger.info("scheduled job " + jobIdPair + " has heartbeat, set a running timestamp");
					jobs.get(jobIdPair).setRunningTimestamp();
				}
			}
			
			// if the the running job hasn't sent heartbeats for some time, something
			// unexpected has happened for the
			// comp and the job is lost

			for (IdPair jobIdPair : jobs.getRunningJobs().keySet()) {
				JobScheduler jobScheduler = this.getJobScheduler(jobs.get(jobIdPair));
				Instant lastHeartbeat = jobScheduler.getLastHeartbeat(jobIdPair);
				
				
				// the job never really started. Probably comp startup failed because of missing runtime or something.
				boolean noHeartbeat = (lastHeartbeat == null);
				
				// not really finished, but lost, but let's try to clean up anyway					
				// the comp pod may have had heartbeat for a moment even when the startup fails
				boolean heartbeatLost = lastHeartbeat != null && lastHeartbeat.until(Instant.now(), ChronoUnit.SECONDS) > heartbeatLostTimeout;
				
				if (noHeartbeat || heartbeatLost) {
					
					String expirationMessage = "heartbeat lost";;
					
					if (noHeartbeat) {						
						expirationMessage = "no heartbeat";
					}			
				
					// let's try collect the comp logs to show to reason				
					String compLog = null;
					
					try {
						compLog = jobScheduler.getLog(jobIdPair);
						
					} catch (Exception e) {
						logger.error("failed to get comp logs", e);
						compLog = "failed to get comp logs";
					}
						 
					// try to clean-up in any case
					jobScheduler.removeFinishedJob(jobIdPair);
					expire(jobIdPair, expirationMessage, compLog);
				}
			}
		}
		
//		logger.info(RestUtils.asJson(this.getStatus(), false));
	}
	
	private void schedule(IdPair idPair, SchedulerJob jobState) {
		
		boolean slotsPerUserReached = false;
		
		synchronized (jobs) {

			int userNewCount = jobs.getNewSlots(jobState.getUserId());
			int userScheduledCount = jobs.getScheduledSlots(jobState.getUserId());
			int userRunningCount = jobs.getRunningSlots(jobState.getUserId());
			
			// check if user is allowed to run jobs of this size
			if (jobState.getSlots() > this.maxScheduledAndRunningSlotsPerUser) {
				logger.info("user " + jobState.getUserId() + " doesn't have enough slots for the tool");
				this.jobs.remove(idPair);
				this.endJob(idPair, JobState.ERROR,
						"Not enough job slots. The tool requires " + jobState.getSlots()
								+ " slots but your user account can have only "
								+ this.maxScheduledAndRunningSlotsPerUser + " slots running.", null);
				return;
			}
			
			// fail the job immediately if user has created thousands of new jobs
			if (userNewCount + jobState.getSlots() > this.maxNewSlotsPerUser) {
				logger.info("max job count of new jobs (" + this.maxNewSlotsPerUser + ") reached by user "
						+ jobState.getUserId());

				this.jobs.remove(idPair);
				this.endJob(idPair, JobState.ERROR,
						"Maximum number of new jobs per user (" + this.maxNewSlotsPerUser + ") reached.", null);
				return;
			}
			
			// keep in queue if normal personal limit of running jobs is reached
			if (userRunningCount + userScheduledCount + jobState.getSlots() > this.maxScheduledAndRunningSlotsPerUser) {
				logger.info("max job count of running jobs (" + this.maxScheduledAndRunningSlotsPerUser
						+ ") reached by user " + jobState.getUserId());
				// user's slot quota is full
				// keep the job in queue and try again later
				slotsPerUserReached = true;
			}



			// schedule
			
			// set the schedule timestamp to be able to calculate user's slot quota when many jobs are started at the same time
			if (!slotsPerUserReached) {
				jobState.setScheduleTimestamp();
			}
		}
		
		if (slotsPerUserReached) {
			
			// update job after releasing the this.jobs lock
			HashSet<JobState> allowedStates = new HashSet<>() {{ 
				add(JobState.NEW); 
				add(JobState.WAITING); 
			}};
			
			try {
				this.bashJobScheduler.updateDbJob(idPair, JobState.WAITING, "max job count reached", null, allowedStates);
				
			} catch (IllegalStateException | RestException e) {
				logger.error("failed to update job " + idPair.toString(), e);
				return;
			}			
			
		} else {
		
			JobScheduler jobScheduler = this.getJobScheduler(jobState);
			logger.info("schedule job " + idPair + " using " + jobScheduler.getClass().getSimpleName());
			jobScheduler.scheduleJob(idPair, jobState.getSlots(), jobState.getTool(), jobState.getRuntime());
		}
	}

	

	/**
	 * Move from NEW to EXPIRED_WAITING
	 * 
	 * @param jobId
	 * @param reason
	 * @param screenOutput 
	 */
	public void expire(IdPair jobId, String reason, String screenOutput) {
		logger.warn("expire job " + jobId + ": " + reason);
		jobs.remove(jobId);
		endJob(jobId, JobState.EXPIRED_WAITING, reason, screenOutput);
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
	private void endJob(IdPair jobId, JobState jobState, String reason, String screenOutput) {
		try {
			Job job = sessionDbClient.getJob(jobId.getSessionId(), jobId.getJobId());
			job.setEndTime(Instant.now());
			job.setState(jobState);
			job.setStateDetail("Job state " + jobState + " (" + reason + ")");
			
			if (screenOutput != null) {
				if (job.getScreenOutput() != null && job.getScreenOutput().isEmpty()) {
					// we set screen output only for jobs that shouldn't have even started, at least for now
					// otherwise we need to decide which one to keep
					logger.warn("job " + jobId + " already had screen output");
				}
				job.setScreenOutput(screenOutput);
			}
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
		status.put("scheduledJobCount", jobs.getScheduledJobs().size());
				
		status.put("newSlotCount", SchedulerJobs.getSlots(jobs.getNewJobs().values()));
		status.put("runningSlotCount", SchedulerJobs.getSlots(jobs.getRunningJobs().values()));
		status.put("scheduledSlotCount", SchedulerJobs.getSlots(jobs.getScheduledJobs().values()));
		
		status.putAll(this.offerJobScheduler.getStatus());
		status.putAll(this.bashJobScheduler.getStatus());
		
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
	public void newResourcesAvailable(JobScheduler jobScheduler) {
		
		List<IdPair> newJobs = null;
		
		synchronized (jobs) {
			
			newJobs = jobs.getNewJobs().entrySet().stream()
					.filter(e -> getJobScheduler(jobs.get(e.getKey())) == jobScheduler)
					.sorted((e1, e2) -> e1.getValue().getNewTimestamp().compareTo(e2.getValue().getNewTimestamp()))
					.map(e -> e.getKey()).collect(Collectors.toList());
		}
		
		if (newJobs.size() > 0) {
			logger.info("rescheduling " + newJobs.size() + " waiting jobs in " + jobScheduler.getClass().getSimpleName());
			for (IdPair idPair : newJobs) {
				SchedulerJob jobState = jobs.get(idPair);
				
				schedule(idPair, jobState);
			}
		}
	}

	@Override
	public void busy(IdPair idPair) {
		synchronized (jobs) {
			jobs.get(idPair).removeScheduled();
		}
	}
}
