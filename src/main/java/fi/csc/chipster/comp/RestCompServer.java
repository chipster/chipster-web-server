package fi.csc.chipster.comp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.websocket.MessageHandler;

import org.apache.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.websocket.WebSocketClient;
import fi.csc.chipster.scheduler.JobCommand;
import fi.csc.chipster.scheduler.JobCommand.Command;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.toolbox.Toolbox;
import fi.csc.chipster.toolbox.ToolboxClient;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.microarray.comp.CompException;
import fi.csc.microarray.comp.CompJob;
import fi.csc.microarray.comp.OldToolboxClient;
import fi.csc.microarray.comp.ResultCallback;
import fi.csc.microarray.comp.RuntimeRepository;
import fi.csc.microarray.comp.ToolRuntime;
import fi.csc.microarray.constants.ApplicationConstants;
import fi.csc.microarray.filebroker.FileBrokerClient;
import fi.csc.microarray.messaging.JobState;
import fi.csc.microarray.messaging.message.GenericJobMessage;
import fi.csc.microarray.messaging.message.GenericResultMessage;
import fi.csc.microarray.messaging.message.JobLogMessage;
import fi.csc.microarray.service.KeepAliveShutdownHandler;
import fi.csc.microarray.service.ShutdownCallback;
import fi.csc.microarray.util.SystemMonitorUtil;

/**
 * Executes analysis jobs and handles input&output. Uses multithreading 
 * and thread pool.
 * 
 * @author Taavi Hupponen, Aleksi Kallio, Petri Klemelä
 */
public class RestCompServer implements ShutdownCallback, ResultCallback, MessageHandler.Whole<String> {

	public static final String DESCRIPTION_OUTPUT_NAME = "description";
	public static final String SOURCECODE_OUTPUT_NAME = "sourcecode";
	
	/**
	 * Loggers.
	 */
	private static Logger logger;
	private static Logger loggerJobs;
	private static Logger loggerStatus;
	
	/**
	 * Directory for storing input and output files.
	 */
	private int scheduleTimeout;
	private int offerDelay;
	private int timeoutCheckInterval;
	private int heartbeatInterval;
	private int compAvailableInterval;
	private boolean sweepWorkDir;
	private int maxJobs;
	
	/**
	 * Id of the comp server instance.
	 */
	private UUID compId = UUID.randomUUID();
	
	private File workDir;
	
	
	private RuntimeRepository runtimeRepository;
	private ToolboxClient toolboxClient;
	private Toolbox toolbox;
	
	
	private FileBrokerClient fileBroker;
	
	/**
	 * Java utility for multithreading.
	 */
	private ExecutorService executorService;
	

	// synchronize with this object when accessing the job maps below
	private Object jobsLock = new Object(); 
	private LinkedHashMap<String, CompJob> scheduledJobs = new LinkedHashMap<String, CompJob>();
	private LinkedHashMap<String, CompJob> runningJobs = new LinkedHashMap<String, CompJob>();
	private Timer timeoutTimer;
	private Timer heartbeatTimer;
	private Timer compAvailableTimer;
	private String localFilebrokerPath;
	private String overridingFilebrokerIp;
	
	volatile private boolean stopGracefully;
	private ServiceLocatorClient serviceLocator;
	private WebSocketClient schedulerClient;
	private String schedulerUri;
	private Config config;
	private AuthenticationClient authClient;
	private SessionDbClient sessionDbClient;
	
	/**
	 * 
	 * @throws Exception 
	 */
	public RestCompServer(String configURL) throws Exception {
		
		// initialise dir, config and logging
//		DirectoryLayout.initialiseServerLayout(
//		        Arrays.asList(new String[] {"comp"}), configURL);
//		Configuration configuration = DirectoryLayout.getInstance().getConfiguration();

		Config config = new Config();
		
		// Initialise instance variables
		this.scheduleTimeout = config.getInt(Config.KEY_COMP_SCHEDULE_TIMEOUT);
		this.offerDelay = config.getInt(Config.KEY_COMP_OFFER_DELAY);
		this.timeoutCheckInterval = config.getInt(Config.KEY_COMP_TIMEOUT_CHECK_INTERVAL);
		this.heartbeatInterval = config.getInt(Config.KEY_COMP_JOB_HEARTBEAT_INTERVAL);
		this.compAvailableInterval = config.getInt(Config.KEY_COMP_AVAILABLE_INTERVAL);
		this.sweepWorkDir= config.getBoolean(Config.KEY_COMP_SWEEP_WORK_DIR);
		this.maxJobs = config.getInt(Config.KEY_COMP_MAX_JOBS);
		//this.localFilebrokerPath = nullIfEmpty(configuration.getString("comp", "local-filebroker-user-data-path"));		
		
		logger = Logger.getLogger(RestCompServer.class);
		loggerJobs = Logger.getLogger("jobs");
		loggerStatus = Logger.getLogger("status");

		
		// initialize working directory
		logger.info("starting compute service...");
		this.workDir = new File("jobs-data", compId.toString());
		if (!this.workDir.mkdirs()) {
			throw new IllegalStateException("creating working directory failed");
		}
		
		// initialize executor service
		this.executorService = Executors.newCachedThreadPool();

		// initialize runtime and tools
		this.runtimeRepository = new RuntimeRepository(this.workDir, this.getClass().getClassLoader().getResourceAsStream("runtimes.xml"));
		this.toolbox = new Toolbox(new File("../chipster-tools/modules"));
		this.toolboxClient = new OldToolboxClient(this.toolbox);
					
		// initialize timeout checker
		timeoutTimer = new Timer(true);
		timeoutTimer.schedule(new TimeoutTimerTask(), timeoutCheckInterval, timeoutCheckInterval);
		
		heartbeatTimer = new Timer(true);

		// disable heartbeat for jobs for now
		//heartbeatTimer.schedule(new JobHeartbeatTask(), heartbeatInterval, heartbeatInterval);
		
		compAvailableTimer = new Timer(true);
		compAvailableTimer.schedule(new CompAvailableTask(), compAvailableInterval, compAvailableInterval);
		
		
		
		config = new Config();
		serviceLocator = new ServiceLocatorClient(config);
		authClient = new AuthenticationClient(serviceLocator, "comp", "compPassword");
		schedulerUri = serviceLocator.get(Role.SCHEDULER).get(0) + "events?token=" + authClient.getToken();
		schedulerClient =  new WebSocketClient(schedulerUri, this, true, "comps-scheduler-client");
		sessionDbClient = new SessionDbClient(serviceLocator, authClient.getCredentials());
		fileBroker = new RestFileBrokerClient(sessionDbClient, serviceLocator, authClient);
		
		// create keep-alive thread and register shutdown hook
		KeepAliveShutdownHandler.init(this);
		
		sendCompAvailable();
		
		logger.info("comp is up and running [" + ApplicationConstants.VERSION + "]");
		logger.info("[mem: " + SystemMonitorUtil.getMemInfo() + "]");
	}

	private String nullIfEmpty(String value) {
		if ("".equals(value.trim())) {
			return null;
		} else {
			return value;
		}
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
		synchronized(jobsLock) {
			// check that we have the job as scheduled
			job = scheduledJobs.get(msg.getJobId().toString());
			if (job != null) {
				scheduledJobs.remove(msg.getJobId().toString());
				runningJobs.put(job.getId(), job);

				// run the job
				executorService.execute(job);
				logger.info("Executing job " + job.getToolDescription().getDisplayName() + "(" + job.getToolDescription().getID() + ")" + ", "+ job.getId() + ", " + job.getInputMessage().getUsername()) ;
			} else {
				logger.warn("Got ACCEPT_OFFER for job which is not scheduled.");
			}
		}
	}

	private void removeScheduled(String jobId) {
		logger.debug("Removing scheduled job " + jobId);
		synchronized(jobsLock) {

			if (scheduledJobs.containsKey(jobId)) {
				scheduledJobs.remove(jobId);
				activeJobRemoved();
			}
		}
	}

	private void cancelJob(String jobId) {
		CompJob job;
		synchronized(jobsLock) {
			if (scheduledJobs.containsKey(jobId)) {
				job = scheduledJobs.remove(jobId);
			} else {
				job = runningJobs.remove(jobId);
			}
		}
		
		if (job != null) {
			job.cancel();
		}
		
		// no activeJobRemoved() here because it get's called when the job actually stops
		
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
			loggerJobs.info(
					job.getId() + delimiter + 
					job.getInputMessage().getToolId().replaceAll("\"", "") + delimiter + 
					job.getState() + delimiter + 
					job.getInputMessage().getUsername() + delimiter + 
//					job.getExecutionStartTime().toString()	+ delimiter + 
//					job.getExecutionEndTime().toString() + delimiter + 
					hostname);
		} catch (Exception e) {
			logger.warn("got exception when logging a job to be removed", e);
		}
		logger.debug("comp server removing job " + job.getId() + "(" + job.getState() + ")");
		synchronized(jobsLock) {
			this.runningJobs.remove(job.getId());
		}
		activeJobRemoved();
		
		checkStopGracefully();		
	}
	
	private void checkStopGracefully() {
		if (stopGracefully) {
			synchronized(jobsLock) {
				if (this.scheduledJobs.size() == 0 && this.runningJobs.size() == 0) {
					shutdown();
					System.exit(0);
				}
			}
		}
	}	
	
	private JobLogMessage jobToMessage(CompJob job) {
		
		String hostname = RestUtils.getHostname();
		
		// current jobs in admin-web may not have starTime yet
		Date startTime = job.getExecutionStartTime();
		if (startTime == null) {
			startTime = job.getScheduleTime();
		}
		if (startTime == null) {
			startTime = job.getReceiveTime();
		}
		
		JobLogMessage jobLogMessage = new JobLogMessage(
				job.getInputMessage().getToolId().replaceAll("\"", ""),
				job.getState(),
				job.getStateDetail(),
				job.getId(),
				startTime,
				job.getExecutionEndTime(),
				job.getResultMessage().getErrorMessage(),
				job.getResultMessage().getOutputText(),
				job.getInputMessage().getUsername(),
				hostname);
		
		return jobLogMessage;
	}


	/**
	 * This is the callback method for a job to send the result message. When a job is finished the thread
	 * running a job will clean up all the data files after calling this method. 
	 * 
	 * For this reason, all the data must be sent before this method returns.
	 * 
	 * 
	 */
	public void sendResultMessage(GenericJobMessage jobMessage, GenericResultMessage result) {
		try {
			JobCommand jobCommand = ((RestJobMessage)jobMessage).getJobCommand();
			Job dbJob = sessionDbClient.getJob(jobCommand.getSessionId(), jobCommand.getJobId());
			//FIXME CompJob shouldn't generate a new jobId
			dbJob.setJobId(jobCommand.getJobId());
			dbJob.setScreenOutput(result.getOutputText());
			dbJob.setState(result.getState());
			dbJob.setStateDetail(result.getStateDetail() + result.getErrorMessage());
			dbJob.setSourceCode(result.getSourceCode());
			sessionDbClient.updateJob(jobCommand.getSessionId(), dbJob);
		} catch (RestException e) {
			logger.error("could not update the job", e);
		}

		logger.info("result message sent (" + result.getJobId() + " " + result.getState() + ")");
	}

	public FileBrokerClient getFileBrokerClient() {
		return this.fileBroker;
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
		ToolboxTool toolboxTool = toolboxClient.getTool(dbJob.getToolId());
		
		if (toolboxTool == null) {
			logger.warn("tool not found: " + dbJob.getToolId() + ", jobId " + dbJob.getJobId());
			return;
		}
		
		// ... and the runtime from runtime repo
		ToolRuntime runtime = runtimeRepository.getRuntime(toolboxTool.getRuntime());
		if (runtime == null) {
			logger.warn(String.format("runtime %s for tool %s not found, ignoring job message", toolboxTool.getRuntime(), dbJob.getToolId()));
			return;
		}
		if (runtime.isDisabled()) {
			logger.warn(String.format("runtime %s for tool %s is disabled, ignoring job message", toolboxTool.getRuntime(), dbJob.getToolId()));
			return;
		}
		

		// get factory from runtime and create the job instance
		CompJob job;
		RestJobMessage jobMessage = new RestJobMessage(msg, dbJob);
		try {
			job = runtime.getJobFactory().createCompJob(jobMessage, toolboxTool, this);
			
		} catch (CompException e) {
			logger.warn("could not create job for " + dbJob.getToolId(), e);
			
			// could also just return without sending result, would result in retry by jobmanager
			GenericResultMessage resultMessage = new GenericResultMessage("", JobState.ERROR, "", "Creating job failed", "");
			sendResultMessage(jobMessage, resultMessage);
			return;
		}
		
		// now we know that we can run this job
		// check if we could run it now or later
		synchronized(jobsLock) {
			job.setReceiveTime(new Date());
			
			// could run it now
			if (runningJobs.size() + scheduledJobs.size() < maxJobs) {
				scheduleJob(job, msg);
			}
			
			// no slot to run it now, ignore it
			else {
				sendCompBusy(msg);
				return;
			}
		}
		updateStatus();
	}

	private void scheduleJob(final CompJob job, final JobCommand cmd) {
		synchronized(jobsLock) {
			job.setScheduleTime(new Date());
			scheduledJobs.put(job.getId(), job);
		}	

		// delaying sending of the offer message can be used for
		// prioritising comp instances 
		int delay = offerDelay * (runningJobs.size() + scheduledJobs.size()-1);
		if (delay > 0 ) {
			Timer timer = new Timer("offer-delay-timer", true);
			timer.schedule(new TimerTask() {

				@Override
				public void run() {
					sendOfferMessage(cmd);
					updateStatus();
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
			this.schedulerClient.sendText(RestUtils.asJson(new JobCommand(cmd.getSessionId(), cmd.getJobId(), compId, Command.OFFER)));
		} catch (IOException | InterruptedException e) {
			synchronized(jobsLock) {
				scheduledJobs.remove(cmd.getJobId());
			}
			logger.error("Could not send OFFER for job " + cmd.getJobId());
		}
	}
	
	private void updateStatus() {
		synchronized(jobsLock) {
			loggerStatus.info("scheduled jobs: " + scheduledJobs.size() + 
					", running jobs: " + runningJobs.size());
		}
	}

	private void sendCompAvailable() {
		sendJobCommand(new JobCommand(null, null, compId, Command.AVAILABLE));
	}
	
	private void sendCompBusy(JobCommand cmd) {
		sendJobCommand(new JobCommand(cmd.getSessionId(), cmd.getJobId(), compId, Command.BUSY));
	}
	
	private void sendJobCommand(JobCommand cmd) {
		try {
			logger.info("send " + cmd.getCommand() + " message");
			this.schedulerClient.sendText(RestUtils.asJson(cmd));
		} catch (IOException | InterruptedException e) {
			logger.error("unable to send " + cmd.getCommand() + " message", e);
		}
	}
	
	/**
	 * The order of the jobs in the receivedJobs and scheduledJobs is FIFO. Because of synchronizations 
	 * this does not necessarily strictly correspond to the receiveTime and scheduleTime fields of the
	 * jobs, but is close enough.
	 * 
	 * As the jobs are ordered, it is enough to check the jobs until the first new enough job is found
	 * as the following jobs are newer (almost always, see above).
	 * 
	 * TODO send BUSY if timeout?
	 * 
	 */
	private class TimeoutTimerTask extends TimerTask {
		
		@Override
		public void run() {
			synchronized(jobsLock) {
				
				ArrayList<CompJob> jobsToBeRemoved = new ArrayList<CompJob>();

				// get old scheduled jobs	
				jobsToBeRemoved.clear();
				for (CompJob job: scheduledJobs.values()) {
					if ((System.currentTimeMillis() - scheduleTimeout * 1000) > job.getScheduleTime().getTime()) {
						jobsToBeRemoved.add(job);
					} else {
						break;
					}
				}

				// remove old scheduled jobs
				for (CompJob job: jobsToBeRemoved) {
					scheduledJobs.remove(job.getId());
					logger.debug("Removing old scheduled job: " + job.getId());
					activeJobRemoved();
				}
			}
		}
	}
	
	public class JobHeartbeatTask extends TimerTask {

		@Override
		public void run() {
			synchronized (jobsLock) {
				for (CompJob job : getAllJobs()) {
					job.updateStateToClient();
				}
			}
		}	
	}

	
	public class CompAvailableTask extends TimerTask {

		@Override
		public void run() {
			synchronized (jobsLock) {
				if (runningJobs.size() + scheduledJobs.size() < maxJobs) {
					sendCompAvailable();
				}
			}
		}	
	}

	public void shutdown() {
		logger.info("shutdown requested");

		try {
			schedulerClient.shutdown();
		} catch (IOException e) {
			logger.warn("failed to shutdown scheduler client", e);
		}
		try {
			sessionDbClient.close();
		} catch (IOException e) {
			logger.warn("failed to shutdown session-db client", e);
		}

		logger.info("shutting down");
	}
	
	private synchronized ArrayList<CompJob> getAllJobs() {
		ArrayList<CompJob> allJobs = new ArrayList<CompJob>();
		allJobs.addAll(scheduledJobs.values());
		allJobs.addAll(runningJobs.values());	

		return allJobs;
	}
	
	public static void main(String[] args) throws Exception {
		new RestCompServer(null);
	}
}
