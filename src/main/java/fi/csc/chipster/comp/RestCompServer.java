package fi.csc.chipster.comp;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.comp.resourcemonitor.ProcessMonitoring;
import fi.csc.chipster.comp.resourcemonitor.legacy.ResourceMonitor;
import fi.csc.chipster.comp.resourcemonitor.legacy.ResourceMonitor.ProcessProvider;
import fi.csc.chipster.filebroker.LegacyRestFileBrokerClient;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.rest.websocket.PubSubEndpoint;
import fi.csc.chipster.rest.websocket.WebSocketClient;
import fi.csc.chipster.rest.websocket.WebSocketClosedException;
import fi.csc.chipster.rest.websocket.WebSocketErrorException;
import fi.csc.chipster.scheduler.offer.JobCommand;
import fi.csc.chipster.scheduler.offer.JobCommand.Command;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.toolbox.ToolboxClientComp;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.chipster.toolbox.runtime.Runtime;
import fi.csc.chipster.toolbox.runtime.RuntimeRepository;
import jakarta.websocket.MessageHandler;
import jakarta.ws.rs.core.UriBuilder;

/**
 * Executes analysis jobs and handles input&output. Uses multithreading and
 * thread pool.
 * 
 * @author Taavi Hupponen, Aleksi Kallio, Petri Klemela
 */
public class RestCompServer
		implements ResultCallback, MessageHandler.Whole<String>, ProcessProvider, StatusSource {

	public static final String KEY_COMP_MAX_JOBS = "comp-max-jobs";
	public static final String KEY_COMP_SCHEDULE_TIMEOUT = "comp-schedule-timeout";
	public static final String KEY_COMP_SWEEP_WORK_DIR = "comp-sweep-work-dir";
	public static final String KEY_COMP_TIMEOUT_CHECK_INTERVAL = "comp-timeout-check-interval";
	public static final String KEY_COMP_STATUS_INTERVAL = "comp-status-interval";
	public static final String KEY_COMP_HEARTBEAT_INTERVAL = "comp-heartbeat-interval";
	public static final String KEY_COMP_MODULE_FILTER_NAME = "comp-module-filter-name";
	public static final String KEY_COMP_MODULE_FILTER_MODE = "comp-module-filter-mode";
	public static final String KEY_COMP_RESOURCE_MONITORING_INTERVAL = "comp-resource-monitoring-interval";
	public static final String KEY_COMP_JOB_TIMEOUT = "comp-job-timeout";
	
	public static final String DESCRIPTION_OUTPUT_NAME = "description";
	public static final String SOURCECODE_OUTPUT_NAME = "sourcecode";
	
	public static final String KEY_COMP_OFFER_DELAY = "comp-offer-delay-running-slots";
	private static final String PREFIX_COMP_OFFER_DELAY_REQUESTED_SLOTS = "comp-offer-delay-requested-slots-";

	/**
	 * Loggers.
	 */
	private static Logger logger;

	/**
	 * Directory for storing input and output files.
	 */
	private int scheduleTimeout;	
	private int timeoutCheckInterval;
	private int compStatusInterval;
	private boolean sweepWorkDir;
	private int maxJobs;
	private int jobTimeout;

	/**
	 * Id of the comp server instance.
	 */
	private UUID compId = UUID.randomUUID();

	private File workDir;

	private ToolboxClientComp toolboxClient;

	private LegacyRestFileBrokerClient fileBroker;

	/**
	 * Java utility for multithreading.
	 */
	private ExecutorService executorService;

	// synchronize with this object when accessing the job maps below
	private Object jobsLock = new Object();
	private LinkedHashMap<String, CompJob> scheduledJobs = new LinkedHashMap<String, CompJob>();
	private LinkedHashMap<String, CompJob> runningJobs = new LinkedHashMap<String, CompJob>();
	private Timer timeoutTimer;
	private Timer compAvailableTimer;
	@SuppressWarnings("unused")
	private String localFilebrokerPath;
	@SuppressWarnings("unused")
	private String overridingFilebrokerIp;

	volatile private boolean stopGracefully;
	private String moduleFilterName;
	private String moduleFilterMode;
	private ServiceLocatorClient serviceLocator;
	private WebSocketClient schedulerClient;
	private String schedulerUri;

	private Config config;
	private AuthenticationClient authClient;
	private SessionDbClient sessionDbClient;

	private ResourceMonitor resourceMonitor;
	private int monitoringInterval;
	private HttpServer adminServer;
	private String hostname;
	
	private int offerDelayRunningSlots;
	private HashMap<Integer, Long> offerDelayRequestedSlots;
	private Timer heartbeatTimer;
	private int compHeartbeatInterval;

	/**
	 * 
	 * @param config
	 * @throws Exception
	 */
	public RestCompServer(String configURL, Config config) throws Exception {

		this.config = config;
	}

	public void startServer()
			throws CompException, IOException, InterruptedException, WebSocketErrorException, WebSocketClosedException {

		logger = LogManager.getLogger();
		
		// Initialise instance variables
		this.scheduleTimeout = config.getInt(KEY_COMP_SCHEDULE_TIMEOUT);
		this.offerDelayRunningSlots = config.getInt(KEY_COMP_OFFER_DELAY);
		this.timeoutCheckInterval = config.getInt(KEY_COMP_TIMEOUT_CHECK_INTERVAL);
		this.compStatusInterval = config.getInt(KEY_COMP_STATUS_INTERVAL);
		this.compHeartbeatInterval = config.getInt(KEY_COMP_HEARTBEAT_INTERVAL);
		this.sweepWorkDir = config.getBoolean(KEY_COMP_SWEEP_WORK_DIR);
		this.maxJobs = config.getInt(KEY_COMP_MAX_JOBS);
		// this.localFilebrokerPath = nullIfEmpty(configuration.getString("comp",
		// "local-filebroker-user-data-path"));
		this.moduleFilterName = config.getString(KEY_COMP_MODULE_FILTER_NAME);
		this.moduleFilterMode = config.getString(KEY_COMP_MODULE_FILTER_MODE);
		this.monitoringInterval = config.getInt(KEY_COMP_RESOURCE_MONITORING_INTERVAL);
		this.jobTimeout = config.getInt(KEY_COMP_JOB_TIMEOUT);
		
		this.offerDelayRequestedSlots = getOfferDelayRequestedSlots(config); 				

		// initialize working directory
		logger.info("starting compute service...");
		this.workDir = new File("jobs-data", compId.toString());
		if (!this.workDir.mkdirs()) {
			throw new IllegalStateException("creating working directory " + this.workDir.getAbsolutePath() + " failed");
		}

		// initialize executor service
		this.executorService = Executors.newCachedThreadPool();

		String username = Role.COMP;
		String password = config.getPassword(username);

		serviceLocator = new ServiceLocatorClient(config);
		authClient = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
		serviceLocator.setCredentials(authClient.getCredentials());

		String toolboxUrl = serviceLocator.getInternalService(Role.TOOLBOX).getUri();
		String schedulerUrl = serviceLocator.getInternalService(Role.SCHEDULER).getUri();

		// initialize toolbox client
		this.toolboxClient = new ToolboxClientComp(toolboxUrl);
		logger.info("toolbox client connecting to: " + toolboxUrl);

		// initialize timeout checker
		timeoutTimer = new Timer("timeout timer", true);
		timeoutTimer.schedule(new TimeoutTimerTask(), timeoutCheckInterval, timeoutCheckInterval);

		/* Send heartbeats frequently enough (every 10 seconds) to be able timeout soon
		 * when something goes wrong (30 seconds).
		 */
		heartbeatTimer = new Timer(true);
		heartbeatTimer.schedule(new HeartbeatTask(), compHeartbeatInterval, compHeartbeatInterval);
		
		// send comp available messages only every 30 seconds, because rescheduling all waiting jobs is quite messy
		compAvailableTimer = new Timer(true);
		compAvailableTimer.schedule(new CompAvailableTask(), compStatusInterval, compStatusInterval);

		resourceMonitor = new ResourceMonitor(this, monitoringInterval);

		schedulerUri = UriBuilder.fromUri(schedulerUrl).queryParam(PubSubEndpoint.TOPIC_KEY, "events").toString();
		schedulerClient = new WebSocketClient(schedulerUri, this, true, "comps-scheduler-client",
				authClient.getCredentials());
		sessionDbClient = new SessionDbClient(serviceLocator, authClient.getCredentials(), Role.SERVER);
		fileBroker = new LegacyRestFileBrokerClient(sessionDbClient, serviceLocator, authClient);

		logger.info("starting the admin rest server");

		AdminResource adminResource = new AdminResource(this);
		adminResource.addFileSystem("work", workDir);
		this.adminServer = RestUtils.startAdminServer(adminResource, null, Role.COMP, config, authClient, serviceLocator);
		
		this.hostname = InetAddress.getLocalHost().getHostName();

		sendCompAvailable();

		logger.info("comp is up and running");
		logger.info("[mem: " + SystemMonitorUtil.getMemInfo() + "]");
	}

	public String getName() {
		return "comp";
	}

	@Override
	public void onMessage(String message) {

		try {
			JobCommand schedulerMsg = RestUtils.parseJson(JobCommand.class, message);

			switch (schedulerMsg.getCommand()) {
			case SCHEDULE:
				logger.info("received a schedule message for a job " + schedulerMsg.getJobId());
				scheduleJob(schedulerMsg);

				break;
			case CHOOSE:

				if (compId.equals(schedulerMsg.getCompId())) {
					runJob(schedulerMsg);
					logger.info("offer chosen, running the job...");
				} else {
					removeScheduled(schedulerMsg.getJobId().toString());
					logger.info("offer rejected");
				}
				break;

			case CANCEL:
				logger.info("cancelling the job...");
				cancelJob(schedulerMsg.getJobId().toString());
				break;

			default:
				logger.warn("unknown command: " + schedulerMsg.getCommand());
				break;
			}
			updateStatus();

		} catch (Exception e) {
			logger.error("error in comp when handling a scheduler message", e);
		}
	}

	private void runJob(JobCommand msg) {

		logger.debug("ACCEPT_OFFER for comp: " + msg.getCompId() + " job: " + msg.getJobId());

		CompJob job;
		synchronized (jobsLock) {
			// check that we have the job as scheduled
			job = scheduledJobs.get(msg.getJobId().toString());
			if (job != null) {
				scheduledJobs.remove(msg.getJobId().toString());
				runningJobs.put(job.getId(), job);

				// run the job
				executorService.execute(job);
				logger.info("Executing job " + job.getToolDescription().getDisplayName() + "("
						+ job.getToolDescription().getID() + ")" + ", " + job.getId() + ", "
						+ job.getInputMessage().getUsername());
			} else {
				logger.warn("Got ACCEPT_OFFER for job which is not scheduled.");
			}
		}
	}

	private void removeScheduled(String jobId) {
		logger.debug("Removing scheduled job " + jobId);
		synchronized (jobsLock) {

			if (scheduledJobs.containsKey(jobId)) {
				scheduledJobs.remove(jobId);
				activeJobRemoved();
			}
		}
	}

	private void cancelJob(String jobId) {
		CompJob job;
		synchronized (jobsLock) {
			if (scheduledJobs.containsKey(jobId)) {
				job = scheduledJobs.remove(jobId);
			} else {
				job = runningJobs.remove(jobId);
			}
		}

		if (job != null) {
			job.cancel();
		}

		// no activeJobRemoved() here because it get's called when the job actually
		// stops

	}

	public File getWorkDir() {
		return workDir;
	}

	public boolean shouldSweepWorkDir() {
		return sweepWorkDir;
	}

	public void removeRunningJob(CompJob job) {
		String hostname = RestUtils.getHostname();

		char delimiter = ';';
		try {
			logger.info(job.getId() + delimiter + job.getInputMessage().getToolId().replaceAll("\"", "") + delimiter
					+ job.getState() + delimiter + job.getInputMessage().getUsername() + delimiter +
					// job.getExecutionStartTime().toString() + delimiter +
					// job.getExecutionEndTime().toString() + delimiter +
					hostname + delimiter + ProcessMonitoring.humanFriendly(resourceMonitor.getMaxMem(job.getProcess())));
		} catch (Exception e) {
			logger.warn("got exception when logging a job to be removed", e);
		}
		logger.debug("comp server removing job " + job.getId() + "(" + job.getState() + ")");
		synchronized (jobsLock) {
			this.runningJobs.remove(job.getId());
		}
		activeJobRemoved();

		checkStopGracefully();
	}

	private void checkStopGracefully() {
		if (stopGracefully) {
			synchronized (jobsLock) {
				if (this.scheduledJobs.size() == 0 && this.runningJobs.size() == 0) {
					shutdown();
					System.exit(0);
				}
			}
		}
	}

	/**
	 * This is the callback method for a job to send the result message. When a job
	 * is finished the thread running a job will clean up all the data files after
	 * calling this method.
	 * 
	 * For this reason, all the data must be sent before this method returns.
	 * 
	 * 
	 */
	public void sendResultMessage(GenericJobMessage jobMessage, GenericResultMessage result) {

		if (result.getState() == JobState.CANCELLED) {
			// scheduler has already removed the cancelled job from the session-db
			return;
		}

		CompJob compJob = null;
		
		try {
			JobCommand jobCommand = ((RestJobMessage) jobMessage).getJobCommand();
			Job dbJob = sessionDbClient.getJob(jobCommand.getSessionId(), jobCommand.getJobId());

			dbJob.setStartTime(result.getStartTime());
			dbJob.setEndTime(result.getEndTime());
			dbJob.setScreenOutput(result.getOutputText());
			dbJob.setState(result.getState());
			String details = "";
			if (result.getErrorMessage() != null) {
				details = result.getErrorMessage();
			} else if (result.getStateDetail() != null) {
				details = result.getStateDetail();
			}
			dbJob.setStateDetail(details);
			dbJob.setSourceCode(result.getSourceCode());
			dbJob.setComp(this.hostname);
			
			// tool versions
			CompUtils.addVersionsToDbJob(result, dbJob);
									
			compJob = this.runningJobs.get(jobMessage.getJobId());
			if (compJob != null) {
				dbJob.setMemoryUsage(this.resourceMonitor.getMaxMem(compJob.getProcess()));
			}
						
			sessionDbClient.updateJob(jobCommand.getSessionId(), dbJob);
			
		} catch (RestException e) {
			if (e.getResponse().getStatus() == 403) {
				logger.warn("unable to update job, cancel it (" + e.getMessage() + ")");
				// call the job directly, because updating session-db will fail
				if (compJob != null) {
					compJob.cancelRequested();
				}
			} else {
				logger.error("could not update the job", e);
			}
		}

		logger.info("result message sent (" + result.getJobId() + " " + result.getState() + ")");
	}

	public LegacyRestFileBrokerClient getFileBrokerClient() {
		return this.fileBroker;
	}

	public ToolboxClientComp getToolboxClient() {
		return this.toolboxClient;
	}

	private void activeJobRemoved() {
		this.updateStatus();
		sendCompAvailable();
	}

	private void scheduleJob(JobCommand msg) {

		// don't accept new jobs when shutting down
		if (stopGracefully) {
			return;
		}

		Job dbJob = null;
		try {
			dbJob = sessionDbClient.getJob(msg.getSessionId(), msg.getJobId());
		} catch (RestException e) {
			logger.warn("unable to get the job " + msg.getJobId(), e);
			return;
		}

		// get tool from toolbox along with the runtime name
		String toolId = dbJob.getToolId();
		if (toolId == null || toolId.isEmpty()) {
			logger.warn("invalid tool id: " + toolId);
			return;
		}

		ToolboxTool toolboxTool = null;
		try {
			toolboxTool = toolboxClient.getTool(toolId);
		} catch (Exception e) {
			logger.warn("failed to get tool " + toolId + " from toolbox", e);
			return;
		}
		if (toolboxTool == null) {
			logger.warn("tool " + toolId + " not found");
			return;
		}

		if (("exclude".equals(moduleFilterMode) && toolboxTool.getModule().equals(moduleFilterName))
				|| ("include".equals(moduleFilterMode) && !toolboxTool.getModule().equals(moduleFilterName))) {
			logger.warn("tool " + toolId + " in module " + toolboxTool.getModule() + " disabled by module filter");
			return;
		}

		// ... and the runtime from runtime repo
		Runtime runtime;
		try {
			runtime = this.toolboxClient.getRuntime(toolboxTool.getRuntime());
		} catch (RestException e1) {
			logger.warn("failed to get the runtime", e1);
			return;
		}
		if (runtime == null) {
			logger.warn(String.format("runtime %s for tool %s not found, ignoring job message",
					toolboxTool.getRuntime(), dbJob.getToolId()));
			return;
		}

		// get factory from runtime and create the job instance
		CompJob job;
		RestJobMessage jobMessage = new RestJobMessage(msg, dbJob);

		try {
			JobFactory jobFactory = RuntimeRepository.getJobFactory(runtime, config, workDir, toolId);
			job = jobFactory.createCompJob(jobMessage, toolboxTool, this, jobTimeout, dbJob, runtime);

		} catch (CompException e) {
			logger.warn("could not create job for " + dbJob.getToolId(), e);

			// could also just return without sending result, would result in retry by
			// jobmanager
			GenericResultMessage resultMessage = new GenericResultMessage("", JobState.ERROR, "", "Creating job failed",
					"");
			sendResultMessage(jobMessage, resultMessage);
			return;
		}

		// now we know that we can run this job
		// check if we could run it now or later
		synchronized (jobsLock) {
			job.setReceiveTime(new Date());

			int runningSlots = getSlotSum(runningJobs.values());
			int schedSlots = getSlotSum(scheduledJobs.values());
			int requestedSlots = job.getToolDescription().getSlotCount();

			logger.debug("running slots " + runningSlots + " sceduled slots " + schedSlots + " requested slots "
					+ requestedSlots);
			if (runningSlots + schedSlots + requestedSlots <= maxJobs) {
				// could run it now
				scheduleJob(job, msg, runningSlots, schedSlots, requestedSlots);

			} else {
				// no slot to run it now, ignore it
				sendCompBusy(msg);
				return;
			}
		}
		updateStatus();
	}

	private int getSlotSum(Collection<CompJob> jobs) {
		int slots = 0;
		for (CompJob job : jobs) {
			slots += job.getToolDescription().getSlotCount();
		}
		return slots;
	}

	private void scheduleJob(final CompJob job, final JobCommand cmd, int runningSlots, int scheduledSlots, int requestedSlots) {
		synchronized (jobsLock) {
			job.setScheduleTime(new Date());
			scheduledJobs.put(job.getId(), job);
		}

		// delaying sending of the offer message can be used for
		// prioritising comp instances
		long delay = offerDelayRunningSlots * (runningSlots + scheduledSlots);
		
		// comp can avoid jobs of certain size		
		Long slotDelay = offerDelayRequestedSlots.get((Integer)requestedSlots);
		
		if (slotDelay != null) {
			delay = delay + slotDelay;
		}
		
		if (delay > 0) {
			Timer timer = new Timer("offer-delay-timer", true);
			timer.schedule(new TimerTask() {

				@Override
				public void run() {
					try {
						sendOfferMessage(cmd);
						updateStatus();
					} catch (Exception e) {
						logger.warn("offer failed", e);
					}
				}
			}, delay);
		} else {
			sendOfferMessage(cmd);
		}

		updateStatus();
	}

	private void sendOfferMessage(JobCommand cmd) {
		try {
			logger.info("send " + cmd.getCommand() + " message");
			this.schedulerClient.sendText(
					RestUtils.asJson(new JobCommand(cmd.getSessionId(), cmd.getJobId(), compId, Command.OFFER)));
		} catch (IOException | InterruptedException e) {
			synchronized (jobsLock) {
				scheduledJobs.remove(cmd.getJobId().toString());
			}
			logger.error("Could not send OFFER for job " + cmd.getJobId());
		}
	}

	private void updateStatus() {
		synchronized (jobsLock) {
			logger.info("scheduled jobs: " + scheduledJobs.size() + ", running jobs: " + runningJobs.size());
		}
	}

	private void sendCompAvailable() {
		try {
			sendJobCommand(new JobCommand(null, null, compId, Command.AVAILABLE));
		} catch (IllegalStateException e) {
			// don't fill the log with the stack trace because this isn't anything critical
			logger.warn("sending a comp available message was interrupted: " + e.getMessage());
		}
	}

	private void sendCompBusy(JobCommand cmd) {
		sendJobCommand(new JobCommand(cmd.getSessionId(), cmd.getJobId(), compId, Command.BUSY));
	}

	private void sendJobCommand(JobCommand cmd) {
		try {
			// don't fill logs with heartbeats
			if (cmd.getCommand() != Command.AVAILABLE) {
				logger.debug("send " + cmd.getCommand() + " message");
			}
			this.schedulerClient.sendText(RestUtils.asJson(cmd));
		} catch (IOException | InterruptedException e) {
			logger.error("unable to send " + cmd.getCommand() + " message", e);
		}
	}
	
	private static HashMap<Integer, Long> getOfferDelayRequestedSlots(Config config) {
		
		HashMap<Integer, Long> parsedEntries = new HashMap<Integer, Long>();
		for (Entry<String, String> entry : config.getConfigEntries(PREFIX_COMP_OFFER_DELAY_REQUESTED_SLOTS).entrySet()) {
			String slotsString = entry.getKey();
			String confKey = PREFIX_COMP_OFFER_DELAY_REQUESTED_SLOTS + slotsString;
			int slots;
			try {						
				slots = Integer.parseInt(slotsString);
			} catch (NumberFormatException e) {
				logger.warn("cannot parse slot count from configuration key " + confKey);
				continue;
			}
			try {
				long delay = Long.parseLong(entry.getValue());
				parsedEntries.put(slots, delay);
				logger.info("comp is configured to wait " + delay + " ms for jobs of " + slots + " slot(s)");
			} catch (NumberFormatException e) {
				logger.warn("cannot parse delay. configuration key: " + confKey + ", value: " + entry.getValue());
			}
		}
		return parsedEntries;
	}


	/**
	 * The order of the jobs in the receivedJobs and scheduledJobs is FIFO. Because
	 * of synchronizations this does not necessarily strictly correspond to the
	 * receiveTime and scheduleTime fields of the jobs, but is close enough.
	 * 
	 * As the jobs are ordered, it is enough to check the jobs until the first new
	 * enough job is found as the following jobs are newer (almost always, see
	 * above).
	 * 
	 * TODO send BUSY if timeout?
	 * 
	 */
	private class TimeoutTimerTask extends TimerTask {

		@Override
		public void run() {
			try {
				synchronized (jobsLock) {

					ArrayList<CompJob> jobsToBeRemoved = new ArrayList<CompJob>();

					// get old scheduled jobs
					jobsToBeRemoved.clear();
					for (CompJob job : scheduledJobs.values()) {
						if ((System.currentTimeMillis() - scheduleTimeout * 1000) > job.getScheduleTime().getTime()) {
							jobsToBeRemoved.add(job);
						} else {
							break;
						}
					}

					// remove old scheduled jobs
					for (CompJob job : jobsToBeRemoved) {
						scheduledJobs.remove(job.getId());
						logger.debug("Removing old scheduled job: " + job.getId());
						activeJobRemoved();
					}
				}
			} catch (Exception e) {
				logger.warn("removing old jobs failed", e);
			}
		}
	}

	public class HeartbeatTask extends TimerTask {

		@Override
		public void run() {			
			try {
				synchronized (jobsLock) {
					for (CompJob job : runningJobs.values()) {
						sendJobCommand(new JobCommand(job.getInputMessage().getSessionId(),
								UUID.fromString(job.getId()), compId, Command.RUNNING));
					}
				}
			} catch (Exception e) {
				logger.warn("failed to report the status to scheduler", e);
			}
		}
	}
	
	public class CompAvailableTask extends TimerTask {

		@Override
		public void run() {			
			try {
				synchronized (jobsLock) {
					
					if (runningJobs.size() + scheduledJobs.size() < maxJobs) {
						sendCompAvailable();
					}
				}
			} catch (Exception e) {
				logger.warn("failed to report the status to scheduler", e);
			}
		}
	}


	public void shutdown() {
		logger.info("shutdown requested");

		RestUtils.shutdown("comp-admin", adminServer);

		compAvailableTimer.cancel();
		heartbeatTimer.cancel();
		timeoutTimer.cancel();

		try {
			schedulerClient.shutdown();
		} catch (Exception e) {
			logger.warn("failed to shutdown scheduler client: " + e.getMessage());
		}
		try {
			sessionDbClient.close();
		} catch (Exception e) {
			logger.warn("failed to shutdown session-db client: " + e.getMessage());
		}

		logger.info("shutting down");
	}

	@SuppressWarnings("unused")
	private synchronized ArrayList<CompJob> getAllJobs() {
		ArrayList<CompJob> allJobs = new ArrayList<CompJob>();
		allJobs.addAll(scheduledJobs.values());
		allJobs.addAll(runningJobs.values());

		return allJobs;
	}

	@Override
	public HashSet<Process> getRunningJobProcesses() {
		synchronized (jobsLock) {
			HashSet<Process> jobProcesses = new HashSet<>();

			for (CompJob compJob : runningJobs.values()) {
				if (compJob.getProcess() != null) {
					jobProcesses.add(compJob.getProcess());
				}
			}

			return jobProcesses;
		}
	}

	public HashMap<String, Object> getStatus() {
		HashMap<String, Object> status = new HashMap<>();
		synchronized (jobsLock) {
			status.put("runningJobCount", runningJobs.size());
			status.put("scheduledJobCount", scheduledJobs.size());
			
			status.put("runningSlotCount", getSlotSum(runningJobs.values()));
			status.put("scheduledSlotCount", getSlotSum(scheduledJobs.values()));
		}

		status.put("memoryJobTotal", this.resourceMonitor.getCurrentMem());
		return status;
	}

	public static void main(String[] args) throws Exception {

		RestCompServer server = new RestCompServer(null, new Config());

		try {
			server.startServer();
			
			RestUtils.waitForShutdown("comp service", server.adminServer);
			
		} catch (Exception e) {
			System.err.println("comp startup failed, exiting");
			e.printStackTrace(System.err);
			server.shutdown();
			System.exit(1);
		}
	}
}
