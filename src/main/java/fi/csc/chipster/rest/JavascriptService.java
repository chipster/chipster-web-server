package fi.csc.chipster.rest;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.microarray.config.ConfigurationLoader.IllegalConfigurationException;

/**
 * Main class.
 *
 */
public class JavascriptService {

	private static Logger logger = LogManager.getLogger();

	private String serviceRootPath;
	private Process process;
	private File serviceRoot;

	public JavascriptService(String serviceRootPath) {
		this.serviceRootPath = serviceRootPath;
	}

	public void startServer() throws IOException, IllegalConfigurationException, InterruptedException {

		serviceRoot = new File(serviceRootPath);

		if (!serviceRoot.exists()) {
			throw new IllegalConfigurationException("typescript project " + serviceRootPath + " not found");
		}

		System.out.println("Install dependencies");
		runAndWait("npm", "ci");

		System.out.println("Compile");
		runAndWaitCareless("npm", "run", "build");

		System.out.println("Run");
		ProcessBuilder builder = getProcessBuilder("npm", "start");
		this.process = builder.start();

		// wait a bit to show startup log messages in correct order
		Thread.sleep(5000);

		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				try {
					close();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
	}

	public ProcessBuilder getProcessBuilder(String... command) {
		List<String> commandList = Arrays.asList(command);
		// get the full path of the binary
		commandList.set(0, ProcessUtils.getPath(commandList.get(0)));
		ProcessBuilder builder = new ProcessBuilder(commandList);
		builder.environment().put("PATH", StringUtils.join(ProcessUtils.getPaths(), ":"));
		builder.inheritIO();
		builder.directory(serviceRoot);
		return builder;
	}

	public void runAndWait(String... command) throws IOException, InterruptedException {
		int exitCode = runAndWaitCareless(command);
		if (exitCode != 0) {
			throw new IllegalStateException(
					"error in command " + StringUtils.join(command, " ") + ", exit code " + exitCode);
		}
	}

	public int runAndWaitCareless(String... command) throws InterruptedException, IOException {
		ProcessBuilder builder = getProcessBuilder(command);
		Process process = builder.start();
		return process.waitFor();
	}

	/**
	 * Main method.
	 * 
	 * @param args
	 * @throws IOException
	 * @throws IllegalConfigurationException
	 * @throws InterruptedException
	 */
	public static void main(String[] args) throws IOException, IllegalConfigurationException, InterruptedException {

		if (args.length != 1) {
			System.out.println("1 argument required: path to npm project to run");
			System.exit(1);
		}

		String serviceName = args[0];

		final JavascriptService server = new JavascriptService(serviceName);
		server.startServer();

		server.process.waitFor();
	}

	public void close() throws InterruptedException {
		logger.info("JavaScript service shutting down");
		this.process.destroy();
		this.process.waitFor();
		logger.info("JavaScript service stopped");
	}
}
