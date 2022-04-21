package fi.csc.chipster.comp.resourcemonitor;

import java.io.IOException;
import java.util.HashSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ProcessResourceMonitor {
	
	private static Logger logger = LogManager.getLogger();
	
	private Long pid;
	private HashSet<Long> allPids = new HashSet<>();
	private Long maxMem;
	private Long currentMem;
	private Process javaProcess;
	
	public ProcessResourceMonitor(Process javaProcess) {
		this.javaProcess = javaProcess;
	}
	public Long getMaxMem() {
		return maxMem;
	}

	public Long getCurrentMem() {
		return currentMem;
	}
	
	public void update() throws IOException {
		if (pid == null) {
			this.pid = ProcessMonitoring.getPid(javaProcess);
			allPids.add(pid);
		}
		
		if (pid != null) {
			// remember all pids, even if child process ends and grandchild's ppid will be set to 1
			allPids.addAll(ProcessMonitoring.getChildren(pid, true));
			currentMem = ProcessMonitoring.getTotalMemory(allPids);
			if (this.maxMem == null || currentMem > this.maxMem) {
				this.maxMem = currentMem;
			}
			logger.debug("pid " + pid + " mem " + maxMem + " pid count " + allPids.size());
		}
	}
}