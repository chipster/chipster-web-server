package fi.csc.chipster.scheduler;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;

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
	private static final String CONF_BASH_MAX_SLOTS = "scheduler-bash-max-slots";
	
	Executor executor;
	
	private Logger logger = LogManager.getLogger();
	
	@SuppressWarnings("unused")
	private JobSchedulerCallback scheduler;
	private String runScript;
	private String cancelScript;
	private long bashJobTimerInterval;
	private Timer bashJobTimer;
	private String heartbeatScript;
	
	private BashJobs jobs = new BashJobs();
	private int maxSlots; 

	public BashJobScheduler(JobSchedulerCallback scheduler, Config config) {
		this.scheduler = scheduler;
		
		executor = Executors.newFixedThreadPool(config.getInt(CONF_BASH_THREADS), new ThreadFactoryBuilder().setNameFormat("bash-job-scheduler-executor-%d").build());
		
		this.runScript = config.getString(CONF_BASH_RUN_SCRIPT);
		this.cancelScript = config.getString(CONF_BASH_CANCEL_SCRIPT);
		this.heartbeatScript = config.getString(CONF_BASH_HEARTBEAT_SCRIPT);
		this.maxSlots = config.getInt(CONF_BASH_MAX_SLOTS);
		
		this.bashJobTimerInterval = config.getLong(CONF_BASH_JOB_TIMER_INTERVAL) * 1000;		
		
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

	@Override
	public void scheduleJob(UUID sessionId, UUID jobId, int slots) {
		
		this.executor.execute(() -> {
			
			IdPair idPair = new IdPair(sessionId, jobId);
			boolean isBusy = false;
			
			// set first heartbeat so that Scheduler will count this job to be running
			// set it here in the executor thread, because otherwise jobs could timeout while waiting for the 
			// free executor thread
			synchronized (jobs) {
				
				int heartbeatSlots = BashJobs.getSlots(jobs.getHeartbeatJobs().values());
				
				
				logger.info("slots running: " + heartbeatSlots + ", job: " + slots + ", max: " + maxSlots );
				
				if (heartbeatSlots + slots <= this.maxSlots) {
					
//					logger.info("job " + idPair + " can run now");
					
					jobs.addJob(idPair, slots);
			
					// use the conf key as a name in logs
					this.runSchedulerBash(this.runScript, CONF_BASH_RUN_SCRIPT, sessionId, jobId);
					
				} else {
					
					logger.info("job " + idPair + " must wait, scheduler max slots reached: " + maxSlots);
					
					isBusy = true;
				}
			}
			
			if (isBusy) {
				// don't keep the this.jobs locked
				this.scheduler.busy(idPair);
			}
		});
	}
	
	@Override
	public void cancelJob(UUID sessionId, UUID jobId) {
		
		// use the conf key as a name in logs
		this.runSchedulerBashInExecutor(this.cancelScript, CONF_BASH_CANCEL_SCRIPT, sessionId, jobId);
	}
	
	@Override
	public Instant getLastHeartbeat(UUID sessionId, UUID jobId) {
		synchronized (jobs) {
			BashJob job = this.jobs.get(new IdPair(sessionId, jobId));
			if (job == null) {
				return null;
			}
			return job.getHeartbeatTimestamp();			
		}		
	}
		
	public void checkJob(UUID sessionId, UUID jobId) {
		
		IdPair idPair = new IdPair(sessionId, jobId);
		
		try {
			// use the conf key as a name in logs
			// wait for the bash process and handle the exception, don't use the executor
			this.runSchedulerBash(this.heartbeatScript, CONF_BASH_HEARTBEAT_SCRIPT, sessionId, jobId);
			jobs.get(idPair).setHeartbeatTimestamp();
			
		} catch (Exception e) {
			
			// we don't the this.jobs lock, so anything can happen
			BashJob job = jobs.get(idPair);
			
			if (job == null) {
				logger.info("job check was unsuccessful " + idPair + " but job cannot be found anymore. Probably it just finished");
				
			} else if (job.getHeartbeatTimestamp() != null 
					&& job.getHeartbeatTimestamp().until(Instant.now(),  ChronoUnit.SECONDS) < 30) {

				// the process may have just completed but we just haven't received the event yet
				logger.info("job check was unsuccessful " + idPair + " let's wait a bit more");
				
			} else {
				logger.warn("job check was unsuccessful " + idPair + ", seconds since last heartbeat: " + job.getHeartbeatTimestamp().until(Instant.now(),  ChronoUnit.SECONDS));
				// remove our job, scheduler will eventually notices this and remove its own
				this.jobs.remove(idPair);
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
			this.checkJob(idPair.getSessionId(), idPair.getJobId());
		}
	}
	
	private void runSchedulerBashInExecutor(String bashCommand, String name, UUID sessionId, UUID jobId) {
		this.executor.execute(() -> {
			this.runSchedulerBash(bashCommand, name, sessionId, jobId);
		});
	}
	
	private void runSchedulerBash(String bashCommand, String name, UUID sessionId, UUID jobId) {			
		
		List<String> cmd = Arrays.asList("/bin/bash", "-c", bashCommand);
				
		String cmdString = String.join(" ", cmd);
		
		ProcessBuilder pb = new ProcessBuilder(cmd);
		
		pb.environment().put(ENV_SESSION_ID, sessionId.toString());
		pb.environment().put(ENV_JOB_ID, jobId.toString());
		
		try {
			Process process = pb.start();

			Thread printStdoutThread = null;
			printStdoutThread = ProcessUtils.readLines(process.getInputStream(), line -> logger.info(name + " stdout: " + line));	

			// stderr to logger.error
			Thread stderrThread = ProcessUtils.readLines(process.getErrorStream(), line -> logger.error(name + " stderr: " + line));
				
//			printStdoutThread.join();
//			stderrThread.join();

			Instant bashStart = Instant.now();
			
//			logger.info("wait for the process " + process.pid());
			int exitCode = process.waitFor();
//			logger.info("process finished " + process.pid());
			
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
			logger.error("unexpecter error when executing: " + cmdString, e);
		}
	}

	@Override
	public void removeFinishedJob(UUID sessionId, UUID jobId) {
		synchronized (jobs) {	
			IdPair idPair = new IdPair(sessionId, jobId);
			logger.info("remove finished job " + idPair);
			this.jobs.remove(idPair);
		}
		
		scheduler.newResourcesAvailable();
	}

	public Map<String, Object> getStatus() {
		
		HashMap<String, Object> status = new HashMap<>();
		
		synchronized (jobs) {			
			status.put("bashJobCount", jobs.getAllJobs().size());
		}
		
		return status;
	}
}
