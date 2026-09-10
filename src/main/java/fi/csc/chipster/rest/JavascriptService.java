package fi.csc.chipster.rest;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Run a Node.js service (js/type-service) in a child process
 */
public class JavascriptService implements ServerComponent {

	private static Logger logger = LogManager.getLogger();

	/**
	 * How long to wait for the node process to appear after starting npm
	 */
	private static final int START_TIMEOUT_SECONDS = 10;

	/**
	 * How long to wait for the process tree to exit after asking it to stop, before
	 * killing it
	 */
	private static final int STOP_TIMEOUT_SECONDS = 5;

	/**
	 * How long to wait for the process tree to exit after killing it
	 */
	private static final int KILL_TIMEOUT_SECONDS = 1;

	private String serviceRootPath;
	private Process process;
	private File serviceRoot;

	/**
	 * The descendants of the npm process, as they were right after the start
	 * 
	 * npm passes a signal on only to its own child, so anything that kills npm
	 * alone leaves the node process running. A process that has exited has no
	 * descendants anymore, so after that the node process could not be found and
	 * stopped without this.
	 */
	private volatile List<ProcessHandle> descendantsAtStart = List.of();

	/**
	 * Set by the first close(), for the later ones to return immediately and for
	 * logExit() to know that the exit is expected
	 * 
	 * Both the shutdown hook here and ServerLauncher call close(). The hook calls
	 * it as soon as the JVM shutdown begins, which matters for Ctrl+C: it sends
	 * SIGINT to the whole process group, so npm and node get it at the same time
	 * as the JVM and exit on their own right away, long before ServerLauncher has
	 * closed the other components and got to this one.
	 */
	private final AtomicBoolean closing = new AtomicBoolean();

	public JavascriptService(String serviceRootPath) {
		this.serviceRootPath = serviceRootPath;
	}

	public void startServer() throws IOException, IllegalStateException, InterruptedException {

		serviceRoot = new File(serviceRootPath);

		if (!serviceRoot.exists()) {
			throw new IllegalStateException("typescript project " + serviceRootPath + " not found");
		}

		if (!new File(serviceRoot, "node_modules").exists()) {
			// System.out.println("Remove possible existing node_modules");
			// runAndWait("rm", "-rf", "node_modules");

			System.out.println("Install dependencies");
			// --include=dev: typescript is a devDependency but required for the build step
			runAndWait("npm", "ci", "--include=dev");
		}

		System.out.println("Compile");
		runAndWaitCareless("npm", "run", "build");

		/*
		 * Stop the service when the JVM stops, whatever happens in between.
		 * 
		 * ServerLauncher registers its own shutdown hook only after starting all the
		 * services, and only when it's verbose, so its stop() is not guaranteed to
		 * run: an exception in the startup of a later service, or a non-verbose
		 * launcher, would otherwise leave the node process running and holding the
		 * ports of the service. See also the field closing.
		 */
		Runtime.getRuntime().addShutdownHook(new Thread(this::close, "stop-javascript-service"));

		System.out.println("Run");
		ProcessBuilder builder = getProcessBuilder("npm", "start");
		this.process = builder.start();

		logExit(this.process);

		// wait a bit to show startup log messages in correct order
		Thread.sleep(2000);

		this.descendantsAtStart = waitForNode();
	}

	/**
	 * Wait until npm has started the node process and return the descendants
	 * 
	 * npm starts the service through a shell ("npm start" -> "sh -c ..." -> "node")
	 * and that takes a moment. The tree has to be remembered while it's complete,
	 * see descendantsAtStart, so wait for the node process instead of assuming that
	 * it's there after a fixed delay.
	 * 
	 * @return the descendants of the npm process
	 * @throws InterruptedException
	 */
	private List<ProcessHandle> waitForNode() throws InterruptedException {

		long deadline = System.currentTimeMillis() + START_TIMEOUT_SECONDS * 1000;

		while (true) {
			List<ProcessHandle> descendants = this.process.descendants().toList();

			boolean hasNode = descendants.stream()
					.anyMatch(descendant -> descendant.info().command().orElse("").endsWith("/node"));

			if (hasNode) {
				return descendants;
			}

			if (!this.process.isAlive() || System.currentTimeMillis() > deadline) {
				// logExit() reports the exit, so mention only the timeout
				if (this.process.isAlive()) {
					System.out.println("node process of " + serviceRootPath + " not found in "
							+ START_TIMEOUT_SECONDS + " seconds, found " + descendants.size() + " descendant(s)");
				}
				return descendants;
			}

			Thread.sleep(100);
		}
	}

	/**
	 * Log when the process exits
	 * 
	 * Nothing else waits for the process after startServer(), so without this the
	 * service could die without leaving any trace in the log: a process killed by a
	 * signal prints nothing itself, and ServerLauncher keeps running as if the
	 * service was still there. The first symptom is then an error somewhere else, in
	 * a client of the missing service.
	 * 
	 * npm exits when the script it runs exits, so watching the npm process is
	 * enough to notice that the service is gone.
	 * 
	 * @param process
	 */
	private void logExit(Process process) {
		process.onExit().thenAccept(exited -> {
			if (closing.get()) {
				// this may be lost, because log4j stops early in the shutdown, see close()
				logger.debug("javascript service " + serviceRootPath + " exited, exit code "
						+ exited.exitValue());
			} else {
				// the service wasn't asked to stop, so the JVM is running and log4j works
				logger.error("javascript service " + serviceRootPath + " exited unexpectedly, exit code "
						+ exited.exitValue());
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
	public static void main(String[] args) throws IOException, InterruptedException {

		if (args.length != 1) {
			System.out.println("1 argument required: path to npm project to run");
			System.exit(1);
		}

		String serviceName = args[0];

		final JavascriptService server = new JavascriptService(serviceName);
		server.startServer();

		server.process.waitFor();
	}

	/**
	 * The live processes of the service, npm first
	 * 
	 * Both the descendants from the start and the current ones, because either set
	 * alone can be incomplete: a process that has exited has no descendants
	 * anymore, and the tree could have changed since the start. A ProcessHandle
	 * identifies a process by its start time too, so the dead handles are dropped
	 * before the duplicates, and a dead one can't hide a live process that got the
	 * same pid.
	 * 
	 * @return processes that are still alive
	 */
	private List<ProcessHandle> getTree() {
		return Stream.concat(Stream.of(this.process.toHandle()),
				Stream.concat(this.process.descendants(), this.descendantsAtStart.stream()))
				.filter(ProcessHandle::isAlive)
				.distinct()
				.toList();
	}

	/**
	 * Wait for the processes to exit
	 * 
	 * @param processes
	 * @param timeoutSeconds
	 * @return true if all of them exited in time
	 */
	private boolean waitForExit(List<ProcessHandle> processes, int timeoutSeconds) {

		CompletableFuture<?>[] exits = processes.stream()
				.map(ProcessHandle::onExit)
				.toList()
				.toArray(new CompletableFuture<?>[0]);

		try {
			CompletableFuture.allOf(exits).get(timeoutSeconds, TimeUnit.SECONDS);
			return true;

		} catch (TimeoutException e) {
			return false;

		} catch (InterruptedException | ExecutionException e) {
			System.err.println("error when waiting for the JavaScript service to stop");
			e.printStackTrace();
			return false;
		}
	}

	public void close() {

		if (this.process == null) {
			// startServer() wasn't called, or failed before starting the process
			return;
		}

		if (!this.closing.compareAndSet(false, true)) {
			// already stopped by the shutdown hook or by ServerLauncher
			return;
		}

		/*
		 * Print instead of logging, because this usually runs in a shutdown hook,
		 * where log4j has already stopped its own logging, see
		 * RestUtils.shutdownGracefullyOnInterrupt().
		 */
		System.out.println("JavaScript service " + serviceRootPath + " shutting down");

		/*
		 * Stop the whole process tree. npm runs the service in a grandchild process
		 * ("npm start" -> "sh -c ..." -> "node") and stopping npm alone would leave
		 * the node process running as an orphan, holding the ports of the service.
		 */
		List<ProcessHandle> tree = getTree();

		tree.forEach(ProcessHandle::destroy);

		// wait for all of them, not only for npm, which exits almost immediately
		if (!waitForExit(tree, STOP_TIMEOUT_SECONDS)) {

			System.out.println("JavaScript service " + serviceRootPath + " didn't stop in " + STOP_TIMEOUT_SECONDS
					+ " seconds, killing it");

			tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);

			// don't claim that it stopped without checking
			if (!waitForExit(tree, KILL_TIMEOUT_SECONDS)) {
				List<Long> alive = tree.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
				System.err.println("JavaScript service " + serviceRootPath + " could not be killed, still running: "
						+ alive);
				return;
			}
		}

		System.out.println("JavaScript service " + serviceRootPath + " stopped");
	}
}
