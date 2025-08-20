package fi.csc.chipster.comp;

import java.io.File;
import java.net.InetAddress;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.comp.resourcemonitor.ProcessMonitoring;
import fi.csc.chipster.comp.resourcemonitor.singleshot.SingleShotResourceMonitor;
import fi.csc.chipster.comp.resourcemonitor.singleshot.SingleShotResourceMonitor.SingleShotProcessProvider;
import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.scheduler.SchedulerClient;
import fi.csc.chipster.scheduler.offer.JobCommand;
import fi.csc.chipster.scheduler.resource.SchedulerResource;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Output;
import fi.csc.chipster.toolbox.ToolboxClientComp;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.chipster.toolbox.runtime.Runtime;
import fi.csc.chipster.toolbox.runtime.RuntimeRepository;

/**
 * Executes analysis jobs and handles input&output.
 * 
 * Compile:
 * 
 * ./gradlew distTar; pushd build/tmp/; tar -xzf
 * ../distributions/chipster-web-server.tar.gz; popd
 * 
 * Run:
 * 
 * java -cp build/tmp/chipster-web-server/lib/*:
 * fi.csc.chipster.comp.SingleShotComp
 */
public class SingleShotComp
		implements ResultCallback, SingleShotProcessProvider {

	public static final String KEY_COMP_MAX_JOBS = "comp-max-jobs";
	public static final String KEY_COMP_SCHEDULE_TIMEOUT = "comp-schedule-timeout";
	public static final String KEY_COMP_SWEEP_WORK_DIR = "comp-sweep-work-dir";
	public static final String KEY_COMP_TIMEOUT_CHECK_INTERVAL = "comp-timeout-check-interval";
	public static final String KEY_COMP_STATUS_INTERVAL = "comp-status-interval";
	public static final String KEY_COMP_MODULE_FILTER_NAME = "comp-module-filter-name";
	public static final String KEY_COMP_MODULE_FILTER_MODE = "comp-module-filter-mode";
	public static final String KEY_COMP_RESOURCE_MONITORING_INTERVAL = "comp-resource-monitoring-interval";
	public static final String KEY_COMP_JOB_TIMEOUT = "comp-job-timeout";
	public static final String KEY_COMP_MAX_STORAGE = "comp-max-storage";

	public static final String KEY_COMP_INPUT_FILE_TLS_VERSION = "comp-input-file-tls-version";
	public static final String KEY_COMP_INPUT_FILE_HTTP = "comp-input-file-http2";
	public static final String KEY_COMP_INPUT_FILE_CIPHER = "comp-input-file-cipher";

	public static final String DESCRIPTION_OUTPUT_NAME = "description";
	public static final String SOURCECODE_OUTPUT_NAME = "sourcecode";

	public static final String KEY_COMP_OFFER_DELAY = "comp-offer-delay-running-slots";

	/**
	 * Loggers.
	 */
	private final Logger logger = LogManager.getLogger();

	private final int jobTimeout;

	/**
	 * Id of the comp server instance.
	 */
	private final UUID compId = UUID.randomUUID();

	private final File workDir;

	private ToolboxClientComp toolboxClient;

	private RestFileBrokerClient fileBroker;

	/**
	 * Java utility for multithreading.
	 */
	private ExecutorService executorService;

	// synchronize with this object when accessing the job maps below
	private final Object jobsLock = new Object();
	private CompJob job;

	private ServiceLocatorClient serviceLocator;

	private SessionDbClient sessionDbClient;

	private SingleShotResourceMonitor resourceMonitor;
	private final int monitoringInterval;
	private String hostname;
	private final Config config;
	private Long storageLimit;
	private String inputFileTlsVersion;
	private boolean inputFileHttp2;
	private String inputFileCipher;

	/**
	 * 
	 * @param configURL
	 * @param config
	 * @param sessionToken
	 * @throws Exception
	 */
	public SingleShotComp(String configURL, Config config, String sessionToken) throws Exception {

		logger.info("SingleShotComp started");

		this.config = config;

		// Initialise instance variables
		this.monitoringInterval = config.getInt(KEY_COMP_RESOURCE_MONITORING_INTERVAL);
		this.jobTimeout = config.getInt(KEY_COMP_JOB_TIMEOUT);
		this.inputFileTlsVersion = config.getString(KEY_COMP_INPUT_FILE_TLS_VERSION);
		this.inputFileHttp2 = config.getBoolean(KEY_COMP_INPUT_FILE_HTTP);
		this.inputFileCipher = config.getString(KEY_COMP_INPUT_FILE_CIPHER);

		if (this.inputFileTlsVersion.isEmpty()) {
			this.inputFileTlsVersion = null;
		}
		if (this.inputFileCipher.isEmpty()) {
			this.inputFileCipher = null;
		}

		logger.info("inputFileTlsVersion: " + inputFileTlsVersion);
		logger.info("inputFileHttp2: " + inputFileHttp2);
		logger.info("inputFileCipher: " + inputFileCipher);

		// initialize working directory
		this.workDir = new File("jobs-data", compId.toString());
		if (!this.workDir.mkdirs()) {
			throw new IllegalStateException("creating working directory " + this.workDir.getAbsolutePath() + " failed");
		}

		// initialize executor service
		this.executorService = Executors.newCachedThreadPool();

		StaticCredentials sessionTokenCredentials = new StaticCredentials(TokenRequestFilter.TOKEN_USER, sessionToken);

		// Role.SESSION_TOKEN has access to internal addresses
		serviceLocator = new ServiceLocatorClient(config);
		serviceLocator.setCredentials(sessionTokenCredentials);

		String toolboxUrl = serviceLocator.getInternalService(Role.TOOLBOX).getUri();

		// initialize toolbox client
		this.toolboxClient = new ToolboxClientComp(toolboxUrl);
		logger.info("toolbox client connecting to: " + toolboxUrl);

		resourceMonitor = new SingleShotResourceMonitor(this, monitoringInterval);

		sessionDbClient = new SessionDbClient(serviceLocator, sessionTokenCredentials, Role.SERVER);
		fileBroker = new RestFileBrokerClient(serviceLocator, sessionTokenCredentials, Role.SERVER, inputFileTlsVersion,
				inputFileHttp2, inputFileCipher);

		this.hostname = InetAddress.getLocalHost().getHostName();
	}

	public static void main(String[] args) {

		try {

			if (args.length != 3) {

				// logger of the startup class cannot be static
				System.out.println("wrong number of arguments");
				System.out.println("Usage: " + SingleShotComp.class + " SESSION_ID JOB_ID SESSION_TOKEN");
				System.exit(1);
			}

			UUID sessionId = UUID.fromString(args[0]);
			UUID jobId = UUID.fromString(args[1]);
			String sessionToken = args[2];

			SingleShotComp comp = new SingleShotComp(null, new Config(), sessionToken);

			CompJob compJob = comp.getCompJob(sessionId, jobId);

			comp.runJob(compJob);

		} catch (Exception e) {
			System.err.println("error in comp launch");
			e.printStackTrace();
		}
	}

	private void runJob(CompJob job) {

		synchronized (jobsLock) {
			// check that we have the job as scheduled

			this.job = job;

			// run the job
			executorService.execute(job);

			logger.info("executing job '" + job.getToolDescription().getDisplayName() + "' ("
					+ job.getToolDescription().getID() + ")" + ", " + job.getId() + ", "
					+ job.getInputMessage().getUsername());
		}
	}

	@Override
	public File getWorkDir() {
		return workDir;
	}

	@Override
	public boolean shouldSweepWorkDir() {
		return true;
	}

	@Override
	public void removeRunningJob(CompJob job) {
		String humanReadableHostname = RestUtils.getHostname();

		char delimiter = ';';
		try {
			logger.info(job.getId() + delimiter + job.getInputMessage().getToolId().replaceAll("\"", "") + delimiter
					+ job.getState() + delimiter + job.getStateDetail() + delimiter
					+ job.getInputMessage().getUsername() + delimiter +
					// job.getExecutionStartTime().toString() + delimiter +
					// job.getExecutionEndTime().toString() + delimiter +
					humanReadableHostname + delimiter + ProcessMonitoring.humanFriendly(resourceMonitor.getMaxMem()));
		} catch (Exception e) {
			logger.warn("got exception when logging a job to be removed", e);
		}
		logger.debug("comp server removing job " + job.getId() + "(" + job.getState() + ")");
		synchronized (jobsLock) {
			this.job = null;
		}

		shutdown();
		System.exit(0);
	}

	/**
	 * This is the callback method for a job to send the result message. When a job
	 * is finished the thread running a job will clean up all the data files after
	 * calling this method.
	 * 
	 * For this reason, all the data must be sent before this method returns.
	 * 
	 * @param jobMessage
	 * @param result
	 */
	@Override
	public void sendResultMessage(GenericJobMessage jobMessage, GenericResultMessage result) {

		if (result.getState() == JobState.CANCELLED) {
			// scheduler has already removed the cancelled job from the session-db
			return;
		}

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

			dbJob.setOutputs(result.getOutputIds().stream()
					.map(outputId -> {
						Output output = new Output();
						output.setOutputId(outputId);
						output.setDatasetId(result.getDatasetId(outputId));
						output.setDisplayName(result.getOutputDisplayName(outputId));

						return output;
					})
					.collect(Collectors.toList()));

			// tool versions
			CompUtils.addVersionsToDbJob(result, dbJob);

			// comp fills in parameters, because client can send only partial list
			if (result.getParameters() != null) {
				logger.debug("update parameters in DB");
				dbJob.setParameters(List.copyOf(result.getParameters().values()));
			}

			dbJob.setMemoryUsage(this.resourceMonitor.getMaxMem());
			dbJob.setStorageUsage(this.resourceMonitor.getMaxStorage());

			/*
			 * Set storage limit for jobs that are using the default (null)
			 * 
			 * This field has two related purposes: 1. what user requested (for new jobs) 2.
			 * what limit was eventually used (for old jobs). When user requested the
			 * default (null), then we want to replace it with the actual limit that the app
			 * can show for old jobs. By doing it here in the comp (instead of already in
			 * scheduler), the sceduler can see the original request and decide if a
			 * separate working directory volume is needed or not.
			 * 
			 * Cpu and memory limits are set already in scheduler, because in those the
			 * distinction between the default null vs. 1 slot doesn't matter.
			 */
			if (dbJob.getStorageLimit() == null) {
				dbJob.setStorageLimit(this.storageLimit);
			}

			sessionDbClient.updateJob(jobCommand.getSessionId(), dbJob);

			logger.info("result message sent (" + result.getJobId() + " " + result.getState() + ")");

		} catch (RestException e) {
			logger.error("could not update the job", e);
		} catch (Exception e) {
			logger.error("failed to send result message", e);
		}
	}

	@Override
	public RestFileBrokerClient getFileBrokerClient() {
		return this.fileBroker;
	}

	@Override
	public SessionDbClient getSessionDbClient() {
		return this.sessionDbClient;
	}

	@Override
	public ToolboxClientComp getToolboxClient() {
		return this.toolboxClient;
	}

	private CompJob getCompJob(UUID sessionId, UUID jobId) throws JsonMappingException, JsonProcessingException {

		Job dbJob;
		try {
			dbJob = sessionDbClient.getJob(sessionId, jobId);
		} catch (RestException e) {
			logger.warn("unable to get the job " + jobId, e);
			return null;
		}

		// get tool from toolbox along with the runtime name
		String toolId = dbJob.getToolId();
		if (toolId == null || toolId.isEmpty()) {
			logger.warn("invalid tool id: " + toolId);
			return null;
		}

		ToolboxTool toolboxTool;
		try {
			toolboxTool = toolboxClient.getTool(toolId);
		} catch (Exception e) {
			logger.warn("failed to get tool " + toolId + " from toolbox", e);
			return null;
		}
		if (toolboxTool == null) {
			logger.warn("tool " + toolId + " not found");
			return null;
		}

		// get runtime
		Runtime runtime;
		try {
			runtime = toolboxClient.getRuntime(toolboxTool.getRuntime());
		} catch (RestException e1) {
			logger.warn("failed to get the runtime " + toolboxTool.getRuntime() + " from toolbox", e1);
			return null;
		}

		if (runtime == null) {
			logger.warn(String.format("runtime %s for tool %s not found, ignoring job message",
					toolboxTool.getRuntime(), dbJob.getToolId()));
			return null;
		}

		// configure storage limit
		// it's not great to configure this in a "get" method, but we don't have the
		// dbJob after this

		Long jobLimit = dbJob.getStorageLimit();

		Long configLimit = null;
		if (!config.getString(KEY_COMP_MAX_STORAGE).isEmpty()) {
			configLimit = config.getLong(KEY_COMP_MAX_STORAGE);
		}

		if (configLimit != null) {
			// comp config can override other limits
			logger.info("storage limit from configuration is " + configLimit + " GiB");

			// convert gigabytes to bytes
			this.storageLimit = configLimit * 1024 * 1024 * 1024;
		} else if (jobLimit != null) {
			logger.info("storage limit from job is " + jobLimit / (1024 * 1024 * 1024) + " GiB");

			// already in bytes
			this.storageLimit = jobLimit;
		} else {
			Integer schedulerLimit = (Integer) new SchedulerClient(
					serviceLocator.getInternalService(Role.SCHEDULER).getUri())
					.getJobQuota().get(SchedulerResource.KEY_DEFAULT_STORAGE);

			logger.info("storage limit from scheduler is " + schedulerLimit + " GiB");

			// convert gigabytes to bytes
			this.storageLimit = schedulerLimit * 1024l * 1024 * 1024;
		}

		// get factory from runtime and create the job instance
		CompJob compJob;
		JobCommand jobCommand = new JobCommand(sessionId, jobId, null, null);
		RestJobMessage jobMessage = new RestJobMessage(jobCommand, dbJob);

		try {
			JobFactory jobFactory = RuntimeRepository.getJobFactory(runtime, config, this.workDir, toolId);
			compJob = jobFactory.createCompJob(jobMessage, toolboxTool, this, jobTimeout, dbJob, runtime, this.config);

		} catch (CompException e) {
			logger.warn("could not create job for " + dbJob.getToolId(), e);

			GenericResultMessage resultMessage = new GenericResultMessage("", JobState.ERROR, "", "Creating job failed",
					"");
			sendResultMessage(jobMessage, resultMessage);
			return null;
		}

		compJob.setReceiveTime(new Date());

		return compJob;
	}

	public void shutdown() {
		try {
			sessionDbClient.close();
		} catch (Exception e) {
			logger.warn("failed to shutdown session-db client: " + e.getMessage());
		}

		logger.info(this.getClass().getSimpleName() + " is done");
	}

	@Override
	public Process getJobProcess() {
		synchronized (jobsLock) {

			if (this.job != null && this.job.getProcess() != null) {
				return this.job.getProcess();
			}

			return null;
		}
	}

	@Override
	public File getJobDataDir() {
		if (this.job != null && this.job instanceof OnDiskCompJobBase) {
			return ((OnDiskCompJobBase) this.job).getJobDataDir();
		}

		return null;
	}

	@Override
	public void maxStorageChanged(long maxStorage) {

		if (this.storageLimit != null && maxStorage > this.storageLimit) {

			String hfMaxStorage = ProcessMonitoring.humanFriendly(maxStorage);
			String hfStorageLimit = ProcessMonitoring.humanFriendly(storageLimit);

			String message = "storage usage " + hfMaxStorage + " exceeds limit " + hfStorageLimit;

			logger.warn("cancel job: " + message);

			if (this.job != null) {

				// this should trigger scheduler to delete the pod
				job.setErrorMessage(message);
				job.updateState(JobState.ERROR, "storage usage exceeded");

			} else {
				logger.error("storage limit exceeded, but the job is null");
			}
		}
	}

}
