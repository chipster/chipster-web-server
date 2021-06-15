package fi.csc.chipster.scheduler;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.ProcessUtils;

public class BashJobScheduler implements JobScheduler {
	
	private static final String ENV_SESSION_ID = "SESSION_ID";
	private static final String ENV_JOB_ID = "JOB_ID";
	
	private static final String CONF_BASH_THREADS = "scheduler-bash-threads";
	private static final String CONF_BASH_RUN_SCRIPT = "scheduler-bash-run-script";
	private static final String CONF_BASH_CANCEL_SCRIPT = "scheduler-bash-cancel-script";
	private static final String CONF_BASH_HEARTBEAT_SCRIPT = "scheduler-bash-heartbeat-script";
	private static final String CONF_BASH_JOB_TIMER_INTERVAL = "scheduler-bash-job-timer-interval";
	
	Executor executor;
	
	private Logger logger = LogManager.getLogger();
	
	@SuppressWarnings("unused")
	private JobSchedulerCallback scheduler;
	private String runScript;
	private String cancelScript;
	private long bashJobTimerInterval;
	private Timer bashJobTimer;
	private String heartbeatScript;
	
	private HashMap<IdPair, Instant> jobHeartbeats = new HashMap<>(); 

	public BashJobScheduler(JobSchedulerCallback scheduler, Config config) {
		this.scheduler = scheduler;
		
		executor = Executors.newFixedThreadPool(config.getInt(CONF_BASH_THREADS));
		
		this.runScript = config.getString(CONF_BASH_RUN_SCRIPT);
		this.cancelScript = config.getString(CONF_BASH_CANCEL_SCRIPT);
		this.heartbeatScript = config.getString(CONF_BASH_HEARTBEAT_SCRIPT);
		
		this.bashJobTimerInterval = config.getLong(CONF_BASH_JOB_TIMER_INTERVAL) * 1000;		
		
		this.bashJobTimer = new Timer("bash job timer", true);
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

	@Override
	public void scheduleJob(UUID sessionId, UUID jobId) {
		
		synchronized (jobHeartbeats) {
			jobHeartbeats.put(new IdPair(sessionId, jobId), Instant.now());
		}
		
		HashMap<String, String> env = new HashMap<>() {{
			put(ENV_SESSION_ID, sessionId.toString());
			put(ENV_JOB_ID, jobId.toString());
		}};
		
		// use the conf key as a name in logs
		this.runBashInExecutor(this.runScript, env, CONF_BASH_RUN_SCRIPT);
	}
	
	@Override
	public void cancelJob(UUID sessionId, UUID jobId) {
		
		HashMap<String, String> env = new HashMap<>() {{
			put(ENV_SESSION_ID, sessionId.toString());
			put(ENV_JOB_ID, jobId.toString());
		}};
		
		// use the conf key as a name in logs
		this.runBashInExecutor(this.cancelScript, env, CONF_BASH_CANCEL_SCRIPT);
	}
	
	@Override
	public Instant getLastHeartbeat(UUID sessionId, UUID jobId) {
		synchronized (jobHeartbeats) {
			return this.jobHeartbeats.get(new IdPair(sessionId, jobId));			
		}		
	}
		
	public void checkJob(UUID sessionId, UUID jobId) {
		
		HashMap<String, String> env = new HashMap<>() {{
			put(ENV_SESSION_ID, sessionId.toString());
			put(ENV_JOB_ID, jobId.toString());
		}};
		
		IdPair idPair = new IdPair(sessionId, jobId);
		
		try {
			// use the conf key as a name in logs		
			this.runBashInExecutor(this.heartbeatScript, env, CONF_BASH_HEARTBEAT_SCRIPT);
			jobHeartbeats.put(idPair, Instant.now());
		} catch (Exception e) {
			logger.warn("job heartbeat lost " + idPair);			
		}		
	}
	
	private void handleBashJobTimer() {
		
	}
	
	private void runBashInExecutor(String bashCommand, HashMap<String, String> env, String name) {
		this.executor.execute(() -> {
			this.runBash(bashCommand, env, name);
		});
	}
	
	private void runBash(String bashCommand, HashMap<String, String> env, String name) {			
		
		List<String> cmd = Arrays.asList("/bin/bash", "-c", bashCommand);
				
		String cmdString = String.join(" ", cmd);
		
		ProcessBuilder pb = new ProcessBuilder(cmd);
		
		for (String key : env.keySet()) {			
			pb.environment().put(key, env.get(key));
		}
		
		try {
			Process process = pb.start();

			Thread printStdoutThread = null;
			printStdoutThread = ProcessUtils.readLines(process.getInputStream(), line -> logger.info(name + " stdout: " + line));	

			// stderr to logger.error
			Thread stderrThread = ProcessUtils.readLines(process.getErrorStream(), line -> logger.error(name + " stderr: " + line));
				
			printStdoutThread.join();
			stderrThread.join();

			int exitCode = process.waitFor();
			
			if (exitCode == 128 + 9) {
				logger.info(name + " received SIGKILL signal (exit code " + exitCode);				
			} else if (exitCode == 128 + 15) {
				logger.info(name + " received SIGTERM signal (exit code " + exitCode);				
			} else if (exitCode != 0) {
				throw new RuntimeException(cmdString + " failed with exit code " + exitCode);
			}
		} catch (InterruptedException | IOException e) {
			logger.error("unexpecter error when executing: " + cmdString, e);
		}
	}

	@Override
	public void removeFinishedJob(UUID sessionId, UUID jobId) {
		synchronized (jobHeartbeats) {			
			this.jobHeartbeats.remove(new IdPair(sessionId, jobId));
		}
	}
}
