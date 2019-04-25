package fi.csc.chipster.rest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ProcessUtils {
	
	private static Logger logger = LogManager.getLogger();
    
    public static List<String> getPaths() {
		List<String> paths = new ArrayList<String>(Arrays.asList(System.getenv("PATH").split(":")));
		// Eclipse clears the PATH by default, at least on OSX
		paths.add("/usr/local/bin");
		return paths;
    }

	public static String getPath(String command) {		
		for (String path : getPaths()) {
			File binary = new File(new File(path), command);
			if (binary.exists()) {
				logger.debug("using binary " + binary.getAbsolutePath());
				return binary.getAbsolutePath();
			}
		}
		throw new IllegalArgumentException("command " + command + " not found from PATH");
	}

	public static void run(File stdinFile, File stdoutFile, String... cmdArray) throws IOException, InterruptedException {
		run(stdinFile, stdoutFile, null, false, cmdArray);
	}
	
	public static void run(File stdinFile, File stdoutFile, Map<String, String> env, boolean printStdout, String... cmdArray) throws IOException, InterruptedException {
		
		List<String> cmd = Arrays.asList(cmdArray);
		
		String command = cmd.get(0);
		
		// find the absolute path to the binary
		cmd.set(0, getPath(cmd.get(0)));
		
		ProcessBuilder pb = new ProcessBuilder(cmd);
		
		if (env != null) {
			final Map<String, String> pbEnv = pb.environment();		
			pbEnv.putAll(env);
		}

        if (stdoutFile != null) {
        	// stdout to file
        	pb.redirectOutput(stdoutFile);        	
        } else {
        	if (!printStdout) {
	        	// stdout has to but somewhere, otherwise the  process stops when the buffer is full
	        	pb.redirectOutput(new File("/dev/null"));
        	}
        }
				
		Process process = pb.start();
		
		if (printStdout) {
			readLines(process.getInputStream(), line -> logger.info(command + " stdout: " + line));	
		}
		
		// stderr to logger.error
		readLines(process.getErrorStream(), line -> logger.error(command + " stderr: " + line));
		
		if (stdinFile != null) {
			// show progress to know whether the process continues after an error
			try (OutputStream processStdin = process.getOutputStream()) {
				copyWithProgressLogging(new FileInputStream(stdinFile), processStdin, command + " progress: ", stdinFile.length());
			}
		}
		
		int exitCode = process.waitFor();
		if (exitCode != 0) {
			throw new RuntimeException(command + " failed with exit code " + exitCode);
		}
	}
	
	private static void copyWithProgressLogging(InputStream in, OutputStream out, String message, long totalSize) throws IOException {
		// only final (effectively) variables can be used inside the function
		boolean[] isProgressPrinted = new boolean[] { false };
		try (CountingInputStream is = new CountingInputStream(in)) {
			Timer timer = new Timer();
			timer.scheduleAtFixedRate(new TimerTask() {
				@Override
				public void run() {
					isProgressPrinted[0] = true;
					logger.info(message + " " + FileUtils.byteCountToDisplaySize(is.getByteCount()) + " / " + FileUtils.byteCountToDisplaySize(totalSize));
				}					
			}, 1_000, 10_000);		
			IOUtils.copy(is, out);
			if (isProgressPrinted[0]) {
				// print once more to show that it completed			
				logger.info(message + " " + FileUtils.byteCountToDisplaySize(is.getByteCount()) + " / " + FileUtils.byteCountToDisplaySize(totalSize));
			}
			timer.cancel();
		}
	}
	
public static String runStdoutToString(File stdinFile, String... cmdArray) throws IOException, InterruptedException {
		
		List<String> cmd = Arrays.asList(cmdArray);
		
		String command = cmd.get(0);
		
		// find the absolute path to the binary
		cmd.set(0, getPath(cmd.get(0)));
		
		ProcessBuilder pb = new ProcessBuilder(cmd);
				
		Process process = pb.start();
		
		ArrayList<String> stdoutLines = new ArrayList<>(); 
		readLines(process.getInputStream(), stdoutLines::add);			
		
		// stderr to logger.error
		readLines(process.getErrorStream(), line -> logger.error(command + " stderr: " + line));
		
		if (stdinFile != null) {
			try (OutputStream processStdin = process.getOutputStream()) {
				IOUtils.copy(new FileInputStream(stdinFile), processStdin);
			}
		}
		
		int exitCode = process.waitFor();
		if (exitCode != 0) {
			throw new RuntimeException(command + " failed with exit code " + exitCode);
		}
		return String.join("\n", stdoutLines);
	}

	private static void readLines(InputStream inputStream, Consumer<String> f) {
		new Thread(new Runnable() {
			@Override
			public void run() {
		        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        		reader.lines().forEach(line -> {
        			f.accept(line);
		        });
			}			
		}).start();
	}
}
