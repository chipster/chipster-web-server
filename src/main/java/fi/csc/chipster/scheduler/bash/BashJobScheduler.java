package fi.csc.chipster.scheduler.bash;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.ProcessUtils;
import fi.csc.chipster.scheduler.IdPair;
import fi.csc.chipster.scheduler.JobScheduler;
import fi.csc.chipster.scheduler.JobSchedulerCallback;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.chipster.toolbox.runtime.Runtime;

public class BashJobScheduler implements JobScheduler {

	private static final String ENV_SESSION_ID = "SESSION_ID";
	private static final String ENV_JOB_ID = "JOB_ID";
	private static final String ENV_SLOTS = "SLOTS";
	private static final String ENV_IMAGE = "IMAGE";
	private static final String ENV_SESSION_TOKEN = "SESSION_TOKEN";
	private static final String ENV_POD_NAME = "POD_NAME";
	private static final String ENV_TOOLS_BIN_VOLUME = "TOOLS_BIN_VOLUME";
	private static final String ENV_TOOLS_BIN_PATH = "TOOLS_BIN_PATH";
	private static final String ENV_STORAGE = "STORAGE";
	private static final String ENV_POD_YAML = "POD_YAML";
	private static final String ENV_PVC_YAML = "PVC_YAML";
	private static final String ENV_STORAGE_CLASS = "STORAGE_CLASS";

	private static final String CONF_BASH_THREADS = "scheduler-bash-threads";
	private static final String CONF_BASH_SCRIPT_DIR_IN_JAR = "scheduler-bash-script-dir-in-jar";
	private static final String CONF_BASH_RUN_SCRIPT = "scheduler-bash-run-script";
	private static final String CONF_BASH_CANCEL_SCRIPT = "scheduler-bash-cancel-script";
	private static final String CONF_BASH_FINISHED_SCRIPT = "scheduler-bash-finished-script";
	private static final String CONF_BASH_HEARTBEAT_SCRIPT = "scheduler-bash-heartbeat-script";
	private static final String CONF_BASH_LOG_SCRIPT = "scheduler-bash-log-script";
	private static final String CONF_BASH_POD = "scheduler-bash-pod";
	private static final String CONF_BASH_PVC = "scheduler-bash-pvc";
	private static final String CONF_BASH_JOB_TIMER_INTERVAL = "scheduler-bash-job-timer-interval";
	private static final String CONF_BASH_MAX_SLOTS = "scheduler-bash-max-slots";
	private static final String CONF_BASH_HEARTBEAT_LOST_TIMEOUT = "scheduler-bash-heartbeat-lost-timeout";
	private static final String CONF_TOKEN_VALID_TIME = "scheduler-bash-token-valid-time";
	private static final String CONF_BASH_IMAGE_REPOSITORY = "scheduler-bash-image-repository";
	private static final String CONF_BASH_STORAGE_CLASS = "scheduler-bash-storage-class";
	private static final int POD_NAME_MAX_LENGTH = 63;


	private ThreadPoolExecutor bashExecutor;

	private Logger logger = LogManager.getLogger();

	private JobSchedulerCallback scheduler;
	private String runScript;
	private String cancelScript;
	private long bashJobTimerInterval;
	private Timer bashJobTimer;
	private String heartbeatScript;

	private BashJobs jobs = new BashJobs();
	private int maxSlots;
	private long heartbeatLostTimeout;
	private String finishedScript;
	private SessionDbClient sessionDbClient;
	private long tokenValidTime;

	private String scriptDirInJar;
	private String imageRepository;
	private Config config;
	private String logScript;
	private String podYaml;
	private String pvcYaml;
	private String storageClass;

	public BashJobScheduler(JobSchedulerCallback scheduler, SessionDbClient sessionDbClient,
			ServiceLocatorClient serviceLocator, Config config) throws IOException {
		this.config = config;
		this.scheduler = scheduler;
		this.sessionDbClient = sessionDbClient;

		ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("bash-job-scheduler-executor-%d")
				.build();
		int executorThreads = config.getInt(CONF_BASH_THREADS);
		
		// limited pool for bash scripts to avoid running out of memory and overloading other systems 
		this.bashExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(executorThreads, threadFactory);

		this.runScript = config.getString(CONF_BASH_RUN_SCRIPT);
		this.cancelScript = config.getString(CONF_BASH_CANCEL_SCRIPT);
		this.finishedScript = config.getString(CONF_BASH_FINISHED_SCRIPT);
		this.heartbeatScript = config.getString(CONF_BASH_HEARTBEAT_SCRIPT);
		this.logScript = config.getString(CONF_BASH_LOG_SCRIPT);
		this.scriptDirInJar = config.getString(CONF_BASH_SCRIPT_DIR_IN_JAR);
		this.imageRepository = config.getString(CONF_BASH_IMAGE_REPOSITORY);
		this.storageClass = config.getString(CONF_BASH_STORAGE_CLASS);

		if (this.runScript.isEmpty()) {
			this.runScript = readJarFile(scriptDirInJar + "/run.bash");
		}

		if (this.cancelScript.isEmpty()) {
			this.cancelScript = readJarFile(scriptDirInJar + "/cancel.bash");
		}

		if (this.finishedScript.isEmpty()) {
			this.finishedScript = readJarFile(scriptDirInJar + "/finished.bash");
		}

		if (this.heartbeatScript.isEmpty()) {
			this.heartbeatScript = readJarFile(scriptDirInJar + "/heartbeat.bash");
		}

		if (this.logScript.isEmpty()) {
			this.logScript = readJarFile(scriptDirInJar + "/log.bash");
		}

		this.maxSlots = config.getInt(CONF_BASH_MAX_SLOTS);

		this.podYaml = this.getFromJarOrConf(CONF_BASH_POD, this.scriptDirInJar, "pod.yaml");
		this.pvcYaml = this.getFromJarOrConf(CONF_BASH_PVC, this.scriptDirInJar, "pvc.yaml");

		this.bashJobTimerInterval = config.getLong(CONF_BASH_JOB_TIMER_INTERVAL) * 1000;
		this.heartbeatLostTimeout = config.getLong(CONF_BASH_HEARTBEAT_LOST_TIMEOUT);
		this.tokenValidTime = config.getLong(CONF_TOKEN_VALID_TIME);

		this.bashJobTimer = new Timer("bash-job-scheduler-timer", true);
		this.bashJobTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				// catch exceptions to keep the timer running
				try {
					handleBashJobTimer();
				} catch (Exception e) {
					logger.error("error in job timer", e);
				}
			}
		}, bashJobTimerInterval, bashJobTimerInterval);
	}

	private String getFromJarOrConf(String confKey, String dirInJar, String filename) throws IOException {

		String confValue = config.getString(confKey);

		if (!confValue.isEmpty()) {

			logger.info("read " + confKey + " from conf");
			return confValue;
			
		} else {	
			
			File jarFile = new File(scriptDirInJar, filename);

			String jarFileContents = this.readJarFile(jarFile.getPath());

			if (jarFileContents != null) {
				logger.info("read jar path " + jarFile);
				return jarFileContents;
			}

			logger.info("no " + confKey + " or jar path " + jarFile + " was found");
			return null;

		} 
	}

	public String readJarFile(String path) throws IOException {

		InputStream is = this.getClass().getClassLoader().getResourceAsStream(path);

		if (is == null) {
			return null;
		}

		return new String(is.readAllBytes(), StandardCharsets.UTF_8);
	}

	@Override
	public void addRunningJob(IdPair idPair, int slots, String toolId) {
		synchronized (jobs) {
			jobs.addJob(idPair, slots, toolId);
		}
	}

	@Override
	public void scheduleJob(IdPair idPair, int slots, ToolboxTool tool, Runtime runtime) {

		boolean run = false;
		boolean isBusy = false;

		// set first heartbeat so that Scheduler will count this job to be running
		// set it here in the executor thread, because otherwise jobs could timeout
		// while waiting for the
		// free executor thread
		synchronized (jobs) {

			int heartbeatSlots = BashJobs.getSlots(jobs.getHeartbeatJobs().values());

			logger.info("slots running: " + heartbeatSlots + ", job: " + slots + ", max: " + maxSlots);

			if (heartbeatSlots + slots <= this.maxSlots) {

//					logger.info("job " + idPair + " can run now");

				jobs.addJob(idPair, slots, tool.getId());

				run = true;

			} else {

				logger.info("job " + idPair + " must wait, scheduler max slots reached: " + maxSlots);

				isBusy = true;
			}
		}

		// don't keep the this.jobs locked
		if (run) {

			String sessionToken = null;

			try {
				/*
				 * Scheduler can create session token to any session
				 * 
				 * If the user was allowed to create the job in the first place, we can give
				 * access to the whole session too. In the current security model the job
				 * creation required read-write access to the whole session anyway.
				 * 
				 * If users are able to edit tool scripts in the future, they can get this token
				 * from the comp.
				 */
				sessionToken = sessionDbClient.createSessionToken(idPair.getSessionId(),
						this.tokenValidTime * 24 * 60 * 60);
			} catch (RestException e) {
				logger.error("failed to get the session token for the job " + idPair, e);
				synchronized (jobs) {
					jobs.remove(idPair);
				}
				this.scheduler.expire(idPair, "failed to get the session token", null);
				return;
			}

			HashMap<String, String> env = getEnv(tool, runtime, idPair, slots, sessionToken);

			// use the conf key as a name in logs
			this.runSchedulerBash(this.runScript, CONF_BASH_RUN_SCRIPT, env, null);
		}

		if (isBusy) {
			this.scheduler.busy(idPair);
		}
	}
	
	/**
	 * Get minimal env, enough for pod monitoring and deletion
	 * 
	 * 
	 * @param toolId
	 * @param idPair
	 * @return
	 */
	private HashMap<String, String> getEnv(String toolId, IdPair idPair) {
		HashMap<String, String> env = new HashMap<>();
		
		env.put(ENV_SESSION_ID, idPair.getSessionId().toString());
		env.put(ENV_JOB_ID, idPair.getJobId().toString());

		String podName = getPodName(idPair, toolId);
		
		env.put(ENV_POD_NAME, podName);
		
		return env;
	}

	/**
	 * Get the full env, needed for the pod launch
	 * 
	 * @param tool
	 * @param runtime
	 * @param idPair
	 * @param slots
	 * @param sessionToken
	 * @param compToken
	 * @return
	 */
	private HashMap<String, String> getEnv(ToolboxTool tool, Runtime runtime, IdPair idPair, int slots, String sessionToken) {
		
		HashMap<String, String> env = getEnv(tool.getId(), idPair);
		
		if (slots > 0) {
			env.put(ENV_SLOTS, "" + slots);
		}

		String image = tool.getSadlDescription().getImage();
		
		if (image == null) {
			image = runtime.getImage();
		}
		
		if (image != null) {
			env.put(ENV_IMAGE, this.imageRepository + image);
		}
		
		String toolsBin = runtime.getToolsBinVolume();
		
		if (toolsBin != null) {
			env.put(ENV_TOOLS_BIN_VOLUME, toolsBin);
		}
		
		String toolsBinPath = runtime.getToolsBinPath();
		
		if (toolsBinPath != null) {
			env.put(ENV_TOOLS_BIN_PATH, toolsBinPath);
		}
		
		Integer storage = tool.getSadlDescription().getStorage();
		
		if (storage != null) {
			env.put(ENV_STORAGE, "" + storage);
		}

		if (sessionToken != null) {
			env.put(ENV_SESSION_TOKEN, sessionToken);
		}
		
		env.put(ENV_POD_YAML, this.podYaml);
		env.put(ENV_PVC_YAML, this.pvcYaml);
		env.put(ENV_STORAGE_CLASS, this.storageClass);
		
		return env;
	}
	
	public String getPodName(IdPair idPair, String toolId) {
		String podName = "comp-job-" + idPair.getJobId().toString() + "-" + toolId;

		// max pod name length in Kubernetes
		if (podName.length() > POD_NAME_MAX_LENGTH) {
			podName = podName.substring(0, POD_NAME_MAX_LENGTH);
		}
		
		// podname must be in lower case
		podName = podName.toLowerCase();
		
		// replace all special characters (except - and .) with a dash
		podName = podName.replaceAll("[^a-z0-9.-]", "-");
		
		// remove any non-alphanumeric characters from the start and end 
		podName = podName.replaceAll("^[^a-z0-9]*", "");				
		podName = podName.replaceAll("[^a-z0-9]*$", "");

		return podName;
	}

	@Override
	public void cancelJob(IdPair idPair) {

		BashJob job = null;
		
		synchronized (jobs) {
			job = this.jobs.remove(idPair);
		}
		
		if (job == null) {
			logger.warn("unable to cancel job, not found: " + idPair);
			return;
		}
		
		HashMap<String, String> env = getEnv(job.getToolId(), idPair);
		
		// use the conf key as a name in logs
		this.runSchedulerBash(this.cancelScript, CONF_BASH_CANCEL_SCRIPT, env, null);
	}

	@Override
	public void removeFinishedJob(IdPair idPair) {

		BashJob job = null;
		
		synchronized (jobs) {
			logger.info("remove finished job " + idPair);
			job = this.jobs.remove(idPair);
		}
		
		if (job == null) {
			logger.warn("unable to remove finished job, not found: " + idPair);
			return;
		}
		
		HashMap<String, String> env = getEnv(job.getToolId(), idPair);

		this.runSchedulerBash(this.finishedScript, CONF_BASH_FINISHED_SCRIPT, env, null);
	}

	@Override
	public Instant getLastHeartbeat(IdPair idPair) {

		synchronized (jobs) {
			BashJob job = this.jobs.get(idPair);
			if (job == null) {
				return null;
			}
			return job.getHeartbeatTimestamp();
		}
	}

	public void checkJob(IdPair idPair) {

		try {
			
			BashJob job = null; 
			
			synchronized (jobs) {
				job = this.jobs.get(idPair);
			}
			
			if (job == null) {
				logger.warn("job check was unsuccessful, not found: " + idPair);
				return;
			}
			
			HashMap<String, String> env = getEnv(job.getToolId(), idPair);
			
			// use the conf key as a name in logs
			// run in executor to limit the number of external processes
			Future<?> future = this.runSchedulerBash(this.heartbeatScript, CONF_BASH_HEARTBEAT_SCRIPT, env, null);
			
			// wait for the bash process
			future.get();
			
			jobs.get(idPair).setHeartbeatTimestamp();

		} catch (Exception e) {

			// we don't have the this.jobs lock, so anything can happen
			BashJob job = jobs.get(idPair);

			if (job == null) {
				logger.info("job check was unsuccessful " + idPair
						+ " but job cannot be found anymore. Probably it just finished");

			} else if (job.getHeartbeatTimestamp() == null) {
				logger.info("job check was unsuccessful " + idPair + ", let's wait for heartbeat");

			} else if (job.getHeartbeatTimestamp().until(Instant.now(),
					ChronoUnit.SECONDS) < this.heartbeatLostTimeout) {

				// the process may have just completed but we just haven't received the event
				// yet
				logger.info("job check was unsuccessful " + idPair + " let's wait a bit more");

			} else {
				logger.warn("job check was unsuccessful " + idPair + ", seconds since last heartbeat: "
						+ job.getHeartbeatTimestamp().until(Instant.now(), ChronoUnit.SECONDS));

				// remove our job, scheduler will soon notice this, remove its own and call
				// removeFinishedJob()
				synchronized (jobs) {
					this.jobs.remove(idPair);
				}
			}
		}
	}

	private void handleBashJobTimer() {

		HashSet<IdPair> jobKeys = null;

		synchronized (this.jobs) {

			// create copy of the keySet to be able to remove jobs from the original
			// and to release the lock, because checks may take some time
			jobKeys = new HashSet<IdPair>(this.jobs.getAllJobs().keySet());
		}

		for (IdPair idPair : jobKeys) {
			this.checkJob(idPair);
		}
	}

	private Future<?> runSchedulerBash(String bashCommand, String name, Map<String, String> env, StringBuffer stdout) {
				
		Instant startInstant = Instant.now();
		
		return this.bashExecutor.submit(() -> {
			
			long waitTime = startInstant.until(Instant.now(), ChronoUnit.SECONDS);
			
			if (waitTime >= 1) {
				/* It's not easy to fill the pool now, apparently the code is too single threaded.
				 * Let's keep this check anyway, because a full pool would cause random timeouts 
				 * and would be difficult to recognize.
				 */
				logger.warn("waited " + waitTime + " second(s) for the executor. Is more executor threads needed?");
			}
			
			this.runSchedulerBashWithoutExecutor(bashCommand, name, env, stdout);
		});
	}

	private void runSchedulerBashWithoutExecutor(String bashCommand, String name, Map<String, String> env, StringBuffer stdout) {

		List<String> cmd = Arrays.asList("/bin/bash", "-c", bashCommand);

		String cmdString = String.join(" ", cmd);

		ProcessBuilder pb = new ProcessBuilder(cmd);
		
		for (String variableName : env.keySet()) {
			pb.environment().put(variableName, env.get(variableName));
		}

		try {
			Process process = pb.start();

			if (stdout != null) {
				ProcessUtils.readLines(process.getInputStream(), line -> stdout.append(line + "\n"));
			} else {
				ProcessUtils.readLines(process.getInputStream(), line -> logger.info(name + " stdout: " + line));
			}

			ProcessUtils.readLines(process.getErrorStream(), line -> logger.error(name + " stderr: " + line));

			Instant bashStart = Instant.now();

			int exitCode = process.waitFor();

			long bashDuration = bashStart.until(Instant.now(), ChronoUnit.SECONDS);

			if (bashDuration > 10) {
				logger.warn("bash script took " + bashDuration + " seconds (" + cmdString + ")");
			}

			if (exitCode == 128 + 9) {
				logger.info(name + " received SIGKILL signal (exit code " + exitCode);
			} else if (exitCode == 128 + 15) {
				logger.info(name + " received SIGTERM signal (exit code " + exitCode);
			} else if (exitCode != 0) {
				throw new RuntimeException(cmdString + " failed with exit code " + exitCode);
			}
		} catch (InterruptedException | IOException e) {
			logger.error("unexpected error when executing: " + cmdString, e);
		}
	}

	public Map<String, Object> getStatus() {

		HashMap<String, Object> status = new HashMap<>();

		synchronized (jobs) {
			status.put("bashJobCount", jobs.getAllJobs().size());
		}

		return status;
	}

	@Override
	public long getHeartbeatInterval() {
		return this.bashJobTimerInterval;
	}

	@Override
	public String getLog(IdPair idPair) {
		
		BashJob job = null; 
		
		synchronized (jobs) {
			job = this.jobs.get(idPair);
		}
		
		if (job == null) {
			logger.warn("unable to get the log, job not found: " + idPair);
			return null;
		}
		
		StringBuffer logStdout = new StringBuffer();
		
		HashMap<String, String> env = getEnv(job.getToolId(), idPair);
		
		// run in executor to limit the amount of external processes
		Future<?> future = this.runSchedulerBash(this.logScript, CONF_BASH_LOG_SCRIPT, env, logStdout);
		
		try {
			future.get();
		} catch (InterruptedException | ExecutionException e) {
			logger.error("unable to get the log", e);
			return null;
		}
		
		return logStdout.toString();
	}
}
