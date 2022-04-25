package fi.csc.chipster.comp.resourcemonitor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility methods for collecting process information from the ps command. 
 * 
 * @author klemela
 *
 */
public class ProcessMonitoring {
	
	private static Logger logger = LogManager.getLogger();
	
	/**
	 * Some examples for quick tests
	 * 
	 * @param args
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public static void main(String args[]) throws IOException, InterruptedException {
		
		
		// plain command line
		//ProcessBuilder builder = new ProcessBuilder("bash", "-c", "yes | tr \\\\n x | head -c " + 500*1024*1024 + " | grep n");
		
		// allocate 2GB memory in python
		ProcessBuilder builder = new ProcessBuilder("python", "-c", "blob = \" \" * (2**33); import time; time.sleep(20)");
		
		// fork
		//ProcessBuilder builder = new ProcessBuilder("bash", "-c", "python -c 'blob = \" \" * (2**33); import time; time.sleep(10)' & sleep 10");
		
		// fork twice
		//ProcessBuilder builder = new ProcessBuilder("bash", "-c", "bash -c 'python mem-test.py & sleep 10' & sleep 10");
		
		// fork twice and parent terminates
		//ProcessBuilder builder = new ProcessBuilder("bash", "-c", "bash -c 'python mem-test.py & sleep 10' & sleep 3");
		
		builder.redirectErrorStream(true);
		Process p = builder.start();
		
		
		try {
		
		long pid = getPid(p);
		
		// remember child processes even if the parents terminate
		HashSet<Long> allPids = new HashSet<>();
		
		allPids.add(pid);
		
		for (int i = 0; i < 10; i++) {
			printProcess(pid);
			
			allPids.addAll(getChildren(pid, true));
			for (long childPid : allPids) {
				System.out.print("    ");
				printProcess(childPid);
			}
			System.out.println(getTotalMemory(allPids)/1024 + "MB");
			Thread.sleep(1000);
		}
		
		
		IOUtils.copy(p.getInputStream(), System.out);
		IOUtils.copy(p.getErrorStream(), System.err);
		
		} catch (IOException e) {
			// command not available
			e.printStackTrace();
		}
	}
	
	public static long getTotalMemory(Collection<Long> pids) {
		return pids.stream().mapToLong(childPid -> getMemoryWithoutException(childPid)).sum();
	}
	
	public static void printProcess(long pid) throws IOException {
		System.out.println(
				"pid " + pid + 
				" comm " + StringUtils.abbreviate(getCommand(pid), 16) + 
				" rss " + humanFriendly(getMemory(pid)) +
				" ppid " + getParent(pid));
	}
	
	public static String humanFriendly(Long l) {
		if (l == null) {
			return null;
		}
		
		if (l < 1024 * 1024) {
			return "" + l/1024 + " kiB";
		}
		
		if (l < 1024 * 1024 * 1024) {
			return "" + l/1024/1024 + " MiB";
		}
		
		if (l < 1024 * 1024 * 1024 * 1024) {
			return "" + l/1024/1024/1024 + " GiB";
		}
		
		return "" + l/1024/1024/1024/1024 + " TiB";
	}
	
	private static Long getParent(long pid) throws IOException {
		return getPsLong("ppid", pid);
	}

	private static String getCommand(long pid) throws IOException {
		return getPsString("comm", pid);
	}
	
	private static Long getMemoryWithoutException(long pid) {
		try {
			Long mem = getMemory(pid);
			if (mem == null) {
				return 0l;
			}
			return mem;
		} catch (IOException e) {
			logger.warn("exception while getting memory usage", e);
			return 0l;
		}
	}
	
	private static Long getMemory(long pid) throws IOException {		
		Long kilobytes = getPsLong("rss", pid);
		if (kilobytes == null) {
			return null;
		}
		return kilobytes * 1024;
	}
	
	private static Long getPsLong(String psKey, long pid) throws IOException {
		String valueString = getPsString(psKey, pid);
		if (valueString == null) {
			return null;
		}
		return Long.parseLong(valueString);
	}
	
	private static String getPsString(String psKey, long pid) throws IOException {
		String output = execCmd("ps", "-o", psKey, "-p", "" + pid);		
		String[] lines = output.split("\n");
		if (lines.length != 2) {
			return null;
		}
		return lines[1].trim();
	}
	
	public static List<Long> getChildren(long pid, boolean recursive) throws IOException {
		String pidLines = execCmd("pgrep", "-P", "" + pid);
		List<Long> pids = new ArrayList<>();
		for (String pidString : pidLines.split("\n")) {
			if (!pidString.trim().isEmpty()) {
				Long childPid = Long.parseLong(pidString);
				pids.add(childPid);
				if (recursive) {
					pids.addAll(getChildren(childPid, recursive));
				}
			}
		}
		return pids;
	}
	
	public static String execCmd(String... cmd) throws java.io.IOException {
        Process proc = Runtime.getRuntime().exec(cmd);
        try (java.io.InputStream is = proc.getInputStream()) {
        	try (java.io.InputStream es = proc.getErrorStream()) {
        		String err = IOUtils.toString(es, "UTF-8");
        		if (!err.isEmpty()) {
        			throw new RuntimeException("error in command: " + err + "(" + StringUtils.join(cmd, " ") + ")");
        		}
        	}
        	return IOUtils.toString(is, "UTF-8"); 
        }	        
	}
	
	public static Long getPid(Process process) {
		if (process == null) {
			return null;
		}
		
		try {
			return process.pid();
			
		} catch (UnsupportedOperationException e) {
			
			logger.warn("process resource monitoring disabled: " + e.getMessage());
			return null;
		}
	}
}
