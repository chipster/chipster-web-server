package fi.csc.chipster.comp.resourcemonitor.singleshot;

import java.io.File;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.comp.resourcemonitor.ProcessResourceMonitor;
import fi.csc.chipster.comp.resourcemonitor.StorageResourceMonitor;

/**
 * <h1>Monitor resource usage of unix-like processes</h1>
 * 
 * <p>
 * Collect the
 * combined memory usage of each process and its child processes every second.
 * </p>
 * 
 * <p>
 * Java doesn't provide APIs for doing this, so external tools are used instead.
 * There are multiple things that can go wrong or cause inaccuracies, so be
 * prepared for null results.
 * </p>
 * <ul>
 * <li>pid of the process is figured out with the Java Reflection API</li>
 * <li>child processes are parsed from the output of the pgrep command</li>
 * <li>memory usage is parsed from the output of the ps command</li>
 * <li>tracking processes and memory usage by sampling is inaccurate</li>
 * </ul>
 * 
 * <p>
 * Tested only on Linux and OSX.
 * </p>
 * 
 * @author klemela
 *
 */
public class SingleShotResourceMonitor {

	public static interface SingleShotProcessProvider {
		public Process getJobProcess();
		
		public File getJobDataDir();
	}

	private static Logger logger = LogManager.getLogger();

	private ProcessResourceMonitor processMonitor;
	private Timer resourceMonitorTimer;

	private SingleShotProcessProvider processProvider;

	private StorageResourceMonitor storageMonitor;

	public SingleShotResourceMonitor(SingleShotProcessProvider processProvider, int monitoringInterval) {
		if (monitoringInterval >= 0) {
			
			this.processProvider = processProvider;
			
			resourceMonitorTimer = new Timer(true);
			resourceMonitorTimer.schedule(new ResourceMonitorTask(), monitoringInterval, monitoringInterval);
		}
	}

	public class ResourceMonitorTask extends TimerTask {

		@Override
		public void run() {
			try {
				
				updateProcessResources();
				
				updateStorageResources();
				
			} catch (IOException e) {
				logger.error("failed to monitor job resource usage", e);
			}
		}
	}
	
	private void updateProcessResources() throws IOException {
		long t = System.currentTimeMillis();
		
		/* 
		 * Create ProcessResouceMonitor after there is a process
		 * 
		 * We don't know when the CompJob has created the process.
		 * Try to get the process on every timer event and initialize the
		 * ProcessResourceMonitor when the process exists.
		 */
		Process process = processProvider.getJobProcess();
		
		if (process != null && processMonitor == null) {
			processMonitor = new ProcessResourceMonitor(process);					
		}
			
		if (processMonitor != null) {
			processMonitor.update();
		}

		long dt = (System.currentTimeMillis() - t);
		if (dt > 500) {
			// consider getting information of all pids with a single ps process if this
			// happens often
			logger.warn("process monitoring took " + (System.currentTimeMillis() - t) + "ms");
		}
	}
	
	private void updateStorageResources() throws IOException {
		long t = System.currentTimeMillis();
		
		File jobDataDir = processProvider.getJobDataDir();
		
		if (jobDataDir != null && storageMonitor == null) {
			storageMonitor = new StorageResourceMonitor(jobDataDir);					
		}
			
		if (storageMonitor != null) {
			storageMonitor.update();
		}

		long dt = (System.currentTimeMillis() - t);
		if (dt > 500) {
			logger.warn("storage monitoring took " + (System.currentTimeMillis() - t) + "ms");
		}
	}


	public Long getMaxMem() {
		// return null if monitoring is disabled
		if (processMonitor == null) {
			return null;
		}
		return processMonitor.getMaxMem();
	}

	public Long getCurrentMem() {
		// return null if monitoring is disabled
		if (processMonitor == null) {
			return null;
		}
		return processMonitor.getCurrentMem();
	} 
	
	public Long getMaxStorage() {
		if (storageMonitor == null) {
			return null;
		}
		return storageMonitor.getMaxStorage();
	}
	
	public Long getCurrentStorage() {
		if (storageMonitor == null) {
			return null;			
		}
		return storageMonitor.getCurrentStorage();
	}
}
