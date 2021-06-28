package fi.csc.chipster.scheduler.bash;

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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.ProcessUtils;
import fi.csc.chipster.scheduler.IdPair;
import fi.csc.chipster.scheduler.JobScheduler;
import fi.csc.chipster.scheduler.JobSchedulerCallback;

public class BashJobScheduler implements JobScheduler {
	
	private static final String ENV_SESSION_ID = "SESSION_ID";
	private static final String ENV_JOB_ID = "JOB_ID";
	private static final String ENV_SLOTS = "SLOTS";
	private static final String ENV_IMAGE = "IMAGE";
	
	private static final String CONF_BASH_THREADS = "scheduler-bash-threads";
	private static final String CONF_BASH_RUN_SCRIPT = "scheduler-bash-run-script";
	private static final String CONF_BASH_CANCEL_SCRIPT = "scheduler-bash-cancel-script";
	private static final String CONF_BASH_HEARTBEAT_SCRIPT = "scheduler-bash-heartbeat-script";
	private static final String CONF_BASH_JOB_TIMER_INTERVAL = "scheduler-bash-job-timer-interval";
	private static final String CONF_BASH_MAX_SLOTS = "scheduler-bash-max-slots";
	private static final String CONF_BASH_HEARTBEAT_LOST_TIMEOUT= "scheduler-bash-heartbeat-lost-timeout";
	
	private Executor executor;
	
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

	public BashJobScheduler(JobSchedulerCallback scheduler, Config config) {
		this.scheduler = scheduler;
		
		executor = Executors.newFixedThreadPool(config.getInt(CONF_BASH_THREADS), new ThreadFactoryBuilder().setNameFormat("bash-job-scheduler-executor-%d").build());
		
		this.runScript = config.getString(CONF_BASH_RUN_SCRIPT);
		this.cancelScript = config.getString(CONF_BASH_CANCEL_SCRIPT);
		this.heartbeatScript = config.getString(CONF_BASH_HEARTBEAT_SCRIPT);
		this.maxSlots = config.getInt(CONF_BASH_MAX_SLOTS);
		
		this.bashJobTimerInterval = config.getLong(CONF_BASH_JOB_TIMER_INTERVAL) * 1000;
		this.heartbeatLostTimeout = config.getLong(CONF_BASH_HEARTBEAT_LOST_TIMEOUT);
		
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
	public void scheduleJob(IdPair idPair, int slots, String image) {
		
		this.executor.execute(() -> {
			
			boolean run = false;
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
			
					run = true;
					
				} else {
					
					logger.info("job " + idPair + " must wait, scheduler max slots reached: " + maxSlots);
					
					isBusy = true;
				}
			}
			
			// don't keep the this.jobs locked
			if (run) {
				// use the conf key as a name in logs
				this.runSchedulerBash(this.runScript, CONF_BASH_RUN_SCRIPT, idPair, slots, image);
			}
			
			if (isBusy) {
				this.scheduler.busy(idPair);
			}
		});
	}
	
	@Override
	public void cancelJob(IdPair idPair) {
		
		synchronized (jobs) {
			this.jobs.remove(idPair);
		}
		// use the conf key as a name in logs
		this.runSchedulerBashInExecutor(this.cancelScript, CONF_BASH_CANCEL_SCRIPT, idPair, -1, null);
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
			// use the conf key as a name in logs
			// wait for the bash process and handle the exception, don't use the executor
			this.runSchedulerBash(this.heartbeatScript, CONF_BASH_HEARTBEAT_SCRIPT, idPair, -1, null);
			jobs.get(idPair).setHeartbeatTimestamp();
			
		} catch (Exception e) {
			
			// we don't the this.jobs lock, so anything can happen
			BashJob job = jobs.get(idPair);
			
			if (job == null) {
				logger.info("job check was unsuccessful " + idPair + " but job cannot be found anymore. Probably it just finished");
				
			} else if (job.getHeartbeatTimestamp() != null 
					&& job.getHeartbeatTimestamp().until(Instant.now(),  ChronoUnit.SECONDS) < this.heartbeatLostTimeout) {

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
			this.checkJob(idPair);
		}
	}
	
	private void runSchedulerBashInExecutor(String bashCommand, String name, IdPair idPair, int slots, String image) {
		this.executor.execute(() -> {
			this.runSchedulerBash(bashCommand, name, idPair, slots, image);
		});
	}
	
	private void runSchedulerBash(String bashCommand, String name, IdPair idPair, int slots, String image) {			
		
		List<String> cmd = Arrays.asList("/bin/bash", "-c", bashCommand);
				
		String cmdString = String.join(" ", cmd);
		
		ProcessBuilder pb = new ProcessBuilder(cmd);
		
		pb.environment().put(ENV_SESSION_ID, idPair.getSessionId().toString());
		pb.environment().put(ENV_JOB_ID, idPair.getJobId().toString());
		pb.environment().put(ENV_SLOTS, "" + slots);
		pb.environment().put(ENV_IMAGE, image);
		
		try {
			Process process = pb.start();

			ProcessUtils.readLines(process.getInputStream(), line -> logger.info(name + " stdout: " + line));	
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
			logger.error("unexpecter error when executing: " + cmdString, e);
		}
	}

	@Override
	public void removeFinishedJob(IdPair idPair) {

		synchronized (jobs) {	
			logger.info("remove finished job " + idPair);
			this.jobs.remove(idPair);
		}		
	}

	public Map<String, Object> getStatus() {
		
		HashMap<String, Object> status = new HashMap<>();
		
		synchronized (jobs) {			
			status.put("bashJobCount", jobs.getAllJobs().size());
		}
		
		return status;
	}
}
