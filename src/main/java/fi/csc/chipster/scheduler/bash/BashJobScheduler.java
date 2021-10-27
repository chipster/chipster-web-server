package fi.csc.chipster.scheduler.bash;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;

import com.nimbusds.jose.util.IOUtils;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.ProcessUtils;
import fi.csc.chipster.scheduler.IdPair;
import fi.csc.chipster.scheduler.JobScheduler;
import fi.csc.chipster.scheduler.JobSchedulerCallback;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;

public class BashJobScheduler implements JobScheduler {

	private static final String ENV_SESSION_ID = "SESSION_ID";
	private static final String ENV_JOB_ID = "JOB_ID";
	private static final String ENV_SLOTS = "SLOTS";
	private static final String ENV_IMAGE = "IMAGE";
	private static final String ENV_SESSION_TOKEN = "SESSION_TOKEN";
	private static final String ENV_COMP_TOKEN = "COMP_TOKEN";
	private static final String ENV_POD_NAME = "POD_NAME";

	private static final String CONF_BASH_THREADS = "scheduler-bash-threads";
	private static final String CONF_BASH_SCRIPT_DIR_IN_JAR = "scheduler-bash-script-dir-in-jar";
	private static final String CONF_BASH_RUN_SCRIPT = "scheduler-bash-run-script";
	private static final String CONF_BASH_CANCEL_SCRIPT = "scheduler-bash-cancel-script";
	private static final String CONF_BASH_FINISHED_SCRIPT = "scheduler-bash-finished-script";
	private static final String CONF_BASH_HEARTBEAT_SCRIPT = "scheduler-bash-heartbeat-script";
	private static final String CONF_BASH_LOG_SCRIPT = "scheduler-bash-log-script";
	private static final String CONF_BASH_RUN_SCRIPT_STDIN_FILE = "scheduler-bash-run-script-stdin-file";
	private static final String CONF_BASH_JOB_TIMER_INTERVAL = "scheduler-bash-job-timer-interval";
	private static final String CONF_BASH_MAX_SLOTS = "scheduler-bash-max-slots";
	private static final String CONF_BASH_HEARTBEAT_LOST_TIMEOUT = "scheduler-bash-heartbeat-lost-timeout";
	private static final String CONF_TOKEN_VALID_TIME = "scheduler-bash-token-valid-time";
	private static final String CONF_BASH_IMAGE_REPOSITORY = "scheduler-bash-image-repository";
	private static final int POD_NAME_MAX_LENGTH = 63;

	private ThreadPoolExecutor executor;

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
	private AuthenticationClient compAuthClient;
	private String scriptDirInJar;
	private String imageRepository;
	private String runScriptStdin;
	private Config config;
	private String logScript;

	public BashJobScheduler(JobSchedulerCallback scheduler, SessionDbClient sessionDbClient,
			ServiceLocatorClient serviceLocator, Config config) throws IOException {
		this.config = config;
		this.scheduler = scheduler;
		this.sessionDbClient = sessionDbClient;

		// there is a 0.5 second delay in the password authentication. Keep a fresh copy
		// of the SingleShotComp token to avoid that delay in job startup
		String compUsername = Role.SINGLE_SHOT_COMP;
		String compPassword = config.getPassword(compUsername);

		compAuthClient = new AuthenticationClient(serviceLocator, compUsername, compPassword, Role.SINGLE_SHOT_COMP);

		ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("bash-job-scheduler-executor-%d")
				.build();
		int executorThreads = config.getInt(CONF_BASH_THREADS);
		this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(executorThreads, threadFactory);

		this.runScript = config.getString(CONF_BASH_RUN_SCRIPT);
		this.cancelScript = config.getString(CONF_BASH_CANCEL_SCRIPT);
		this.finishedScript = config.getString(CONF_BASH_FINISHED_SCRIPT);
		this.heartbeatScript = config.getString(CONF_BASH_HEARTBEAT_SCRIPT);
		this.logScript = config.getString(CONF_BASH_LOG_SCRIPT);
		this.scriptDirInJar = config.getString(CONF_BASH_SCRIPT_DIR_IN_JAR);
		this.imageRepository = config.getString(CONF_BASH_IMAGE_REPOSITORY);

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

		this.runScriptStdin = this.getScriptStdin(CONF_BASH_RUN_SCRIPT_STDIN_FILE, this.scriptDirInJar);

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

	private String getScriptStdin(String confKey, String scriptDirInJar) throws IOException {

		String stdinPath = config.getString(confKey);

		if (stdinPath.isEmpty()) {
			File jarStdinFile = new File(scriptDirInJar + "/run-stdin.yaml");

			String stdinFromJar = this.readJarFile(jarStdinFile.getPath());

			if (stdinFromJar != null) {
				logger.info("read " + confKey + " from jar path " + jarStdinFile);
				return stdinFromJar;
			}

			logger.info("no " + confKey + " from jar path " + jarStdinFile + " was found");
			return null;

		} else {

			logger.info("read " + confKey + " from path " + stdinPath);
			return IOUtils.readFileToString(new File(stdinPath));
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
	public void scheduleJob(IdPair idPair, int slots, String image, String toolId) {

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

				jobs.addJob(idPair, slots, toolId);

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

			String compToken = compAuthClient.getToken();

			String longImage = this.imageRepository + image;

			// use the conf key as a name in logs
			this.runSchedulerBashInExecutor(this.runScript, CONF_BASH_RUN_SCRIPT, idPair, slots, longImage,
					sessionToken, compToken, this.runScriptStdin, null, toolId);
		}

		if (isBusy) {
			this.scheduler.busy(idPair);
		}
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
		
		// use the conf key as a name in logs
		this.runSchedulerBashInExecutor(this.cancelScript, CONF_BASH_CANCEL_SCRIPT, idPair, -1, null, null, null, null,
				null, job.getToolId());
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

		this.runSchedulerBashInExecutor(this.finishedScript, CONF_BASH_FINISHED_SCRIPT, idPair, -1, null, null, null,
				null, null, job.getToolId());
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
			
			// use the conf key as a name in logs
			// wait for the bash process and handle the exception, don't use the executor
			this.runSchedulerBash(this.heartbeatScript, CONF_BASH_HEARTBEAT_SCRIPT, idPair, -1, null, null, null, null,
					null, job.getToolId());
			
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

	private void runSchedulerBashInExecutor(String bashCommand, String name, IdPair idPair, int slots, String image,
			String sessionToken, String compToken, String stdin, StringBuffer stdout, String toolId) {
		this.executor.execute(() -> {
			this.runSchedulerBash(bashCommand, name, idPair, slots, image, sessionToken, compToken, stdin, stdout, toolId);
		});
	}

	private void runSchedulerBash(String bashCommand, String name, IdPair idPair, int slots, String image,
			String sessionToken, String compToken, String stdin, StringBuffer stdout, String toolId) {
		
		logger.info("command length: " + bashCommand.length() + " bytes");

		List<String> cmd = Arrays.asList("/bin/bash", "-c", bashCommand);

		String cmdString = String.join(" ", cmd);

		ProcessBuilder pb = new ProcessBuilder(cmd);

		pb.environment().put(ENV_SESSION_ID, idPair.getSessionId().toString());
		pb.environment().put(ENV_JOB_ID, idPair.getJobId().toString());

		String podName = "comp-job_" + idPair.getJobId().toString() + "_" + toolId;

		// max pod name length in Kubernetes
		if (podName.length() > POD_NAME_MAX_LENGTH) {
			podName = podName.substring(0, POD_NAME_MAX_LENGTH);
		}
		
		// remove the last character if it's a dot, because that looks like something is broken
		if (podName.endsWith(".")) {
			podName = podName.substring(0, podName.length() - 1);
		}
		
		pb.environment().put(ENV_POD_NAME, podName);

		if (slots > 0) {
			pb.environment().put(ENV_SLOTS, "" + slots);
		}

		if (image != null) {
			pb.environment().put(ENV_IMAGE, image);
		}

		if (sessionToken != null) {
			pb.environment().put(ENV_SESSION_TOKEN, sessionToken);
		}

		if (compToken != null) {
			pb.environment().put(ENV_COMP_TOKEN, compToken);
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

			// the bash script should read the stdin only if a stdin file is provided
			// otherwise it will wait indefinitely
			if (stdin != null) {
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
				// writing everything at once, these shouldn't be very big
				writer.write(stdin);
				writer.flush();
				writer.close();
			}

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
		this.runSchedulerBash(this.logScript, CONF_BASH_LOG_SCRIPT, idPair, -1, null, null, null, null, logStdout, job.getToolId());
		return logStdout.toString();
	}
}
