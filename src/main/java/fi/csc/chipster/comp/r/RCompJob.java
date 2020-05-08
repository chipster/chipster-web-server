/*
 * Created on Jan 27, 2005
 *
 */
package fi.csc.chipster.comp.r;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import fi.csc.chipster.comp.Exceptions;
import fi.csc.chipster.comp.JobCancelledException;
import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.comp.OnDiskCompJobBase;
import fi.csc.chipster.comp.ParameterSecurityPolicy;
import fi.csc.chipster.comp.ParameterValidityException;
import fi.csc.chipster.comp.ProcessMonitor;
import fi.csc.chipster.comp.ProcessPool;
import fi.csc.chipster.comp.ToolDescription;
import fi.csc.chipster.comp.ToolDescription.ParameterDescription;
import fi.csc.chipster.util.IOUtils;

/**
 * Uses R to run actual analysis operations.
 * 
 * @author hupponen, akallio
 */
public class RCompJob extends OnDiskCompJobBase {

	public static final String STRING_DELIMETER = "\"";

	public static final String ERROR_MESSAGE_TOKEN = "Error: ";
	public static final String LAST_LINE_TO_REMOVE_TOKEN = "Execution halted";

	private static final Pattern SUCCESS_STRING_PATTERN = Pattern
			.compile("^\\[.*\\] \"" + SCRIPT_SUCCESSFUL_STRING + "\"$");

	/**
	 * Checks that parameter values are safe to insert into R code. Should closely
	 * match the code that is used to output the values in transformVariable(...).
	 * 
	 * @see RCompJob#transformVariable(String, String, boolean)
	 *
	 */
	public static class RParameterSecurityPolicy extends ParameterSecurityPolicy {

		private static final int MAX_VALUE_LENGTH = 1000;

		/**
		 * This regular expression is very critical, because it checks code that is
		 * directly inserted into R script. Hence it should be very conservative.
		 * 
		 * Interpretation: Maybe minus, zero or more digits, maybe point, zero or more
		 * digits.
		 */
		public static String NUMERIC_VALUE_PATTERN = "-?\\d*\\.?\\d*";

		/**
		 * This regular expression is not very critical, because text is inserted inside
		 * string constant in R code. It should however always be combined with
		 * additional check that string terminator is not contained, because that way
		 * the string constant can be escaped. However values can be used in later
		 * points of the script in very different situations (filenames, etc.) and
		 * should be kept as simple as possible.
		 * 
		 * Interpretation: Only word characters and some special symbols are allowed.
		 * 
		 * This regex should be the same as the one used on client side validation
		 */
		public static String TEXT_VALUE_PATTERN = "[\\p{L}\\p{N}\\-+_:\\.,*() ]*";

		/**
		 * @see ParameterSecurityPolicy#isValueValid(String, ParameterDescription)
		 */
		@Override
		public boolean isValueValid(String value, ParameterDescription parameterDescription) {
			// unset parameters are fine
			if (value == null) {
				return true;
			}

			// Check parameter size (DOS protection)
			if (value.length() > MAX_VALUE_LENGTH) {
				return false;
			}

			// Check parameter content (R injection protection)
			if (parameterDescription.isNumeric()) {

				// Numeric value must match the strictly specified pattern
				return value.matches(NUMERIC_VALUE_PATTERN);

			} else {

				// First check for string termination
				if (value.contains(STRING_DELIMETER)) {
					return false;
				}

				// Text value must still match specified pattern
				return value.matches(TEXT_VALUE_PATTERN);
			}

		}

	}

	public static RParameterSecurityPolicy R_PARAMETER_SECURITY_POLICY = new RParameterSecurityPolicy();

	static final Logger logger = Logger.getLogger(RCompJob.class);

	private CountDownLatch waitProcessLatch = new CountDownLatch(1);

	// injected by handler at right after creation
	private ProcessPool processPool;
	private Process process;
	private ProcessMonitor processMonitor;

	protected RCompJob() {
	}

	/**
	 * Executes the analysis.
	 * 
	 * @throws IOException
	 * @throws MicroarrayException
	 * @throws InterruptedException
	 */
	protected void execute() throws JobCancelledException {
		cancelCheck();
		updateState(JobState.RUNNING, "initialising R");

		List<BufferedReader> inputReaders = new ArrayList<BufferedReader>();

		// load work dir initialiser
		inputReaders.add(new BufferedReader(new StringReader("setwd(\"" + jobDataDir.getAbsolutePath() + "\")\n")));

		// load handler initialiser
		inputReaders.add(new BufferedReader(new StringReader(toolDescription.getInitialiser())));

		// load input parameters
		int i = 0;
		List<String> parameterValues;
		try {
			parameterValues = inputMessage.getParameters(R_PARAMETER_SECURITY_POLICY, toolDescription);

		} catch (ParameterValidityException e) {
			this.setErrorMessage(e.getMessage()); // always has a message
			this.setOutputText(Exceptions.getStackTrace(e));
			updateState(JobState.FAILED_USER_ERROR);
			return;
		}
		for (ToolDescription.ParameterDescription param : toolDescription.getParameters()) {
			String value = parameterValues.get(i);
			String rSnippet = transformVariable(param.getName(), value, param.isNumeric());
			logger.debug("added parameter (" + rSnippet + ")");
			inputReaders.add(new BufferedReader(new StringReader(rSnippet)));
			i++;
		}

		// load input script
		String script = (String) toolDescription.getImplementation();
		inputReaders.add(new BufferedReader(new StringReader(script)));

		// load script finished trigger
		inputReaders.add(new BufferedReader(new StringReader("print(\"" + SCRIPT_SUCCESSFUL_STRING + "\")\n")));

		// get a process
		cancelCheck();
		logger.debug("getting a process.");
		;
		try {
			this.process = processPool.getProcess();
		} catch (Exception e) {
			this.setErrorMessage("Starting R failed.");
			this.setOutputText(Exceptions.getStackTrace(e));
			updateState(JobState.ERROR);
			return;
		}
		boolean processAlive = false;
		try {
			process.exitValue();
		} catch (IllegalThreadStateException itse) {
			processAlive = true;
		}
		if (!processAlive) {
			this.setErrorMessage("Starting R failed.");
			String output = "";
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			try {
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					output += line + "\n";
				}
				reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					output += line + "\n";
				}
			} catch (IOException e) {
				logger.warn("could not read output stream");
			}
			this.setOutputText("R already finished.\n\n" + output);
			updateState(JobState.ERROR);
			return;
		}

		// combine the inputs into a single string and store it as the source code
		StringBuilder inputStringBuilder = new StringBuilder();
		try {
			for (BufferedReader reader : inputReaders) {
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					inputStringBuilder.append(line + "\n");
				}
			}
		} catch (IOException ioe) {
			logger.warn("creating R input failed");
		}

		this.setSourceCode(inputStringBuilder.toString());

		updateState(JobState.RUNNING, "running R");

		// launch the process monitor
		cancelCheck();
		logger.debug("about to start the R process monitor.");
		processMonitor = new ProcessMonitor(process, (screenOutput) -> onScreenOutputUpdate(screenOutput),
				(jobState, screenOutput) -> jobFinished(jobState, "", screenOutput), SUCCESS_STRING_PATTERN);

		new Thread(processMonitor).start();

		// write the input to process
		logger.debug("writing the input to R.");
		BufferedWriter writer = null;
		try {
			writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
			writer.write(inputStringBuilder.toString());
			writer.newLine();
			writer.flush();
		} catch (IOException ioe) {
			// this happens if R has died before or dies while writing the input
			// process monitor will notice this and set state etc
			logger.debug("writing input failed", ioe);
		} finally {
			IOUtils.closeIfPossible(writer);
		}

		// wait for the script to finish
		cancelCheck();
		logger.debug("waiting for the script to finish.");

		boolean finishedBeforeTimeout = false;
		try {
			finishedBeforeTimeout = waitProcessLatch.await(getTimeout(), TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			jobFinished(JobState.ERROR, "Running R was interrupted.", Exceptions.getStackTrace(e));
			return;
		}

		// script now finished or timeout has been reached
		// for cases other than timeout jobFinished is called by ProcessMonitor callback
		cancelCheck();
		logger.debug("done waiting for " + toolDescription.getID() + ", state is " + getState());

		// check if timeout happened
		if (!finishedBeforeTimeout) {
			jobFinished(JobState.TIMEOUT, "R did not finish before timeout.", processMonitor.getOutput());
		}
	}

	@Override
	protected void preExecute() throws JobCancelledException {
		super.preExecute();
	}

	protected void postExecute() throws JobCancelledException {
		super.postExecute();
	}

	protected void cleanUp() {
		try {
			// release the process (don't recycle)
			if (process != null) {
				processPool.releaseProcess(process);
			}
		} catch (Exception e) {
			logger.error("error when releasing process. ", e);
		} finally {
			super.cleanUp();
		}

	}

	/**
	 * Converts a name-value -pair into R variable definition.
	 */
	public static String transformVariable(String name, String value, boolean isNumeric) {

		// Escape strings and such
		if (!isNumeric) {
			if (value != null) {
				value = STRING_DELIMETER + value + STRING_DELIMETER;
			} else {
				value = "NULL";
			}
		}

		// If numeric, check for empty value
		if (isNumeric && value.trim().isEmpty()) {
			value = "NA"; // R's constant for "not available"
		}

		// Sanitize parameter name (remove spaces)
		name = name.replaceAll(" ", "_");

		// Construct and return parameter assignment
		return (name + " <- " + value);
	}

	@Override
	protected void cancelRequested() {
		this.waitProcessLatch.countDown();

	}

	public void setProcessPool(ProcessPool processPool) {
		this.processPool = processPool;
	}

	@Override
	public Process getProcess() {
		return process;
	}

	private synchronized void onScreenOutputUpdate(String screenOutput) {
		if (!this.getState().isFinished()) {
			this.setOutputText(screenOutput);
			updateState(JobState.RUNNING, "running R");
		}
	}

	private synchronized void jobFinished(JobState state, String stateDetails, String screenOutput) {
		try {

			// check if job already finished for some reason like cancel
			if (this.getState().isFinished()) {
				return;
			}

			// add output to result message
			// remove the first line (setwd(...))
			String cleanedOutput = screenOutput.substring(screenOutput.indexOf(("\n")));
			this.setOutputText(cleanedOutput);

			// for a failed job, get error message from screen output
			boolean noteFound = false;
			if (state == JobState.FAILED) {
				String errorMessage = getErrorMessage(cleanedOutput, ERROR_MESSAGE_TOKEN, LAST_LINE_TO_REMOVE_TOKEN);

				// check if error message contains chipster note
				if (errorMessage != null && !errorMessage.isEmpty()) {
					String noteErrorMessage = getChipsterNote(errorMessage);
					if (noteErrorMessage != null) {
						this.setErrorMessage(noteErrorMessage);
						noteFound = true;
					} else {
						this.setErrorMessage(errorMessage);
					}
				} else {
					// set default error message for failed R script
					this.setErrorMessage("Running R script failed.");
				}
			}

			// update state
			if (noteFound) {
				this.updateState(JobState.FAILED_USER_ERROR, stateDetails);
			} else {
				this.updateState(state, stateDetails);
			}
		} finally {
			this.waitProcessLatch.countDown();
		}
	}

}