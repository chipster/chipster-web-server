package fi.csc.chipster.scheduler.offer;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.comp.RestCompServer;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.rest.websocket.PubSubServer;
import fi.csc.chipster.scheduler.IdPair;
import fi.csc.chipster.scheduler.JobScheduler;
import fi.csc.chipster.scheduler.JobSchedulerCallback;
import fi.csc.chipster.scheduler.Scheduler;
import fi.csc.chipster.scheduler.offer.JobCommand.Command;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.chipster.toolbox.runtime.Runtime;
import jakarta.servlet.ServletException;
import jakarta.websocket.MessageHandler;

public class OfferJobScheduler implements MessageHandler.Whole<String>, JobScheduler {

	private static Logger logger = LogManager.getLogger();

	private PubSubServer pubSubServer;

	private JobSchedulerCallback scheduler;

	private OfferJobs jobs = new OfferJobs();

	private Timer jobTimer;

	private long jobTimerInterval;

	private long waitTimeout;

	private Config config;

	public OfferJobScheduler(Config config, AuthenticationClient authService, JobSchedulerCallback scheduler)
			throws ServletException {

		this.config = config;
		this.scheduler = scheduler;

		this.waitTimeout = config.getLong(Scheduler.CONF_WAIT_TIMEOUT);
		this.jobTimerInterval = config.getLong(Scheduler.CONF_JOB_TIMER_INTERVAL) * 1000;

		this.jobTimer = new Timer("websocket job timer", true);
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

		SchedulerTopicConfig topicConfig = new SchedulerTopicConfig(authService);
		this.pubSubServer = new PubSubServer(config.getBindUrl(Role.SCHEDULER), this, topicConfig,
				"scheduler-events");

		this.pubSubServer.setIdleTimeout(config.getLong(Config.KEY_WEBSOCKET_IDLE_TIMEOUT));
		this.pubSubServer.setPingInterval(config.getLong(Config.KEY_WEBSOCKET_PING_INTERVAL));
		this.pubSubServer.start();
	}

	@Override
	public void scheduleJob(IdPair idPair, int slots, Integer storage, ToolboxTool tool, Runtime runtime) {

		synchronized (jobs) {

			OfferJob previousOfferJob = jobs.get(idPair);

			// current comp probably doesn't support non-unique jobIds, but this shouldn't
			// be problem
			// in practice when only finished jobs are copied
			if (jobs.containsJobId(idPair.getJobId())) {
				if (previousOfferJob == null) {
					logger.info("received a new job " + idPair + ", but non-unique jobIds are not supported");
					scheduler.expire(idPair, "non-unique jobId", null);
					return;
				}
				// else the same job is being scheduled again which is fine
			}

			if (previousOfferJob != null && previousOfferJob.getTimeSinceLastScheduled() < waitTimeout) {
				logger.info("don't schedule job " + idPair + " again, because it was just scheduled");

			} else {
				logger.info("schedule job " + idPair);

				jobs.addScheduledJob(idPair);

				JobCommand cmd = new JobCommand(idPair.getSessionId(), idPair.getJobId(), null, Command.SCHEDULE);

				pubSubServer.publish(cmd);
			}
		}
	}

	/**
	 * The job has been cancelled or deleted
	 * 
	 * Inform the comps to cancel the job and remove it from the scheduler. By doing
	 * this here in the scheduler we can handle both waiting and running jobs.
	 * 
	 * @param jobId
	 */
	@Override
	public void cancelJob(IdPair idPair) {

		JobCommand cmd = new JobCommand(idPair.getSessionId(), idPair.getJobId(), null, Command.CANCEL);
		pubSubServer.publish(cmd);

		synchronized (jobs) {

			logger.info("cancel job " + idPair);
			jobs.remove(idPair);
		}
	}

	private void handleJobTimer() {

		HashSet<IdPair> runnableButBusyJobs = new HashSet<>();

		synchronized (jobs) {

			// fast timeout for jobs that are not runnable

			for (IdPair jobIdPair : jobs.getScheduledJobs().keySet()) {
				OfferJob jobState = jobs.get(jobIdPair);

				if (jobState.getTimeSinceLastScheduled() > waitTimeout) {
					if (jobState.isRunnable()) {
						runnableButBusyJobs.add(jobIdPair);
					} else {
						jobs.remove(jobIdPair);
						scheduler.expire(jobIdPair,
								"There was no computing server available to run this job, please inform server maintainers",
								null);
					}
				}
			}
		}

		// don't keep the lock while waiting scheduler to do its job
		for (IdPair idPair : runnableButBusyJobs) {
			this.scheduler.busy(idPair);
		}

		// remove here only after removed from the scheduler first to avoid calls to
		// getLastHeartbeat()
		if (!runnableButBusyJobs.isEmpty()) {
			synchronized (jobs) {
				for (IdPair idPair : runnableButBusyJobs) {
					jobs.remove(idPair);
				}
			}
		}
	}

	/*
	 * React to events from comps
	 */
	@Override
	public void onMessage(String message) {

		try {
			JobCommand compMsg = RestUtils.parseJson(JobCommand.class, message);
			IdPair jobIdPair = new IdPair(compMsg.getSessionId(), compMsg.getJobId());

			this.handleCompMessage(compMsg, jobIdPair);

		} catch (Error e) {
			logger.error("failed to handle comp message", e);
		}
	}

	public void handleCompMessage(JobCommand compMsg, IdPair jobIdPair) {

		switch (compMsg.getCommand()) {
			case OFFER:

				synchronized (jobs) {
					// when comps offer to run a job, pick the first one

					logger.info("received an offer for job " + jobIdPair + " from comp "
							+ Scheduler.asShort(compMsg.getCompId()));
					// respond only to the first offer
					if (jobs.get(jobIdPair) != null) {
						if (!jobs.get(jobIdPair).hasHeartbeat()) {
							jobs.get(jobIdPair).setHeartbeatTimestamp();
							run(compMsg, jobIdPair);
						}
					} else {
						logger.warn("comp " + Scheduler.asShort(compMsg.getCompId())
								+ " sent a offer of an non-existing job "
								+ Scheduler.asShort(jobIdPair.getJobId()));
					}
				}
				break;
			case BUSY:
				synchronized (jobs) {
					// there is a comp that is able to run this job later
					logger.info("job " + jobIdPair + " is runnable on comp " + Scheduler.asShort(compMsg.getCompId()));
					if (jobs.get(jobIdPair) != null) {
						jobs.get(jobIdPair).setRunnableTimestamp();
					} else {
						logger.warn("comp " + Scheduler.asShort(compMsg.getCompId())
								+ " sent a busy message of an non-existing job "
								+ Scheduler.asShort(jobIdPair.getJobId()));
					}
				}
				break;

			case AVAILABLE:

				// when a comp has a free slot, try to schedule all waiting jobs

				// don't lock this.jobs, because this.scheduleJob() will need it soon
				// in another thread, if there are jobs in the queue

				logger.debug("comp available " + Scheduler.asShort(compMsg.getCompId()));
				scheduler.newResourcesAvailable(this);
				break;

			case RUNNING:

				synchronized (jobs) {
					// update the heartbeat timestamps of the running jobs

					logger.debug("job running " + jobIdPair);
					if (jobs.get(jobIdPair) != null) {
						jobs.get(jobIdPair).setHeartbeatTimestamp();
					} else {
						logger.warn("comp " + Scheduler.asShort(compMsg.getCompId())
								+ " sent a heartbeat of an non-existing job "
								+ Scheduler.asShort(jobIdPair.getJobId()));
					}
				}

				break;

			default:
				logger.warn("unknown command: " + compMsg.getCommand());
		}
	}

	/**
	 * Move from SCHEDULED to RUNNING
	 * 
	 * @param compMsg
	 * @param jobId
	 */
	private void run(JobCommand compMsg, IdPair jobId) {
		logger.info("offer for job " + jobId + " chosen from comp " + Scheduler.asShort(compMsg.getCompId()));
		pubSubServer.publish(
				new JobCommand(compMsg.getSessionId(), compMsg.getJobId(), compMsg.getCompId(), Command.CHOOSE));
	}

	public void close() {
		if (pubSubServer != null) {
			pubSubServer.stop();
		}
	}

	public StatusSource getPubSubServer() {
		return this.pubSubServer;
	}

	public Map<String, Object> getStatus() {

		HashMap<String, Object> status = new HashMap<>();
		status.put("offerJobSchedulerScheduledJobCount", jobs.getScheduledJobs().size());
		status.put("offerJobSchedulerHeartbeatJobCount", jobs.getHeartbeatJobs().size());

		return status;
	}

	@Override
	public void removeFinishedJob(IdPair idPair) {
		synchronized (jobs) {
			jobs.remove(idPair);
		}
	}

	@Override
	public Instant getLastHeartbeat(IdPair idPair) {
		synchronized (jobs) {
			OfferJob job = jobs.get(idPair);
			if (job != null) {
				return job.getHeartbeatTimestamp();
			}
			logger.warn("scheduler asked for the last heartbeat of " + idPair + " but there is no such job");
			return null;
		}
	}

	@Override
	public long getHeartbeatInterval() {
		/*
		 * It's not great that this would have to be configured in two services, at
		 * least in theory.
		 * However, this shouldn't be an issue, because it's only used in the scheduler
		 * restarts and rarely
		 * changed anyway.
		 */

		return this.config.getInt(RestCompServer.KEY_COMP_HEARTBEAT_INTERVAL);
	}

	@Override
	public void addRunningJob(IdPair idPair, int slots, Integer storage, ToolboxTool tool) {
		synchronized (jobs) {
			this.jobs.addScheduledJob(idPair);
			this.jobs.get(idPair).setRunnableTimestamp();
		}
	}

	@Override
	public String getLog(IdPair jobIdPair) {
		// admin can check comp logs
		return null;
	}
}
