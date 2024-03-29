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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.comp.Exceptions;
import fi.csc.chipster.comp.JobCancelledException;
import fi.csc.chipster.comp.JobMessageUtils;
import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.comp.OnDiskCompJobBase;
import fi.csc.chipster.comp.ParameterSecurityPolicy;
import fi.csc.chipster.comp.ParameterValidityException;
import fi.csc.chipster.comp.ProcessMonitor;
import fi.csc.chipster.comp.ProcessPool;
import fi.csc.chipster.comp.ToolDescription;
import fi.csc.chipster.comp.ToolUtils;
import fi.csc.chipster.toolbox.sadl.SADLDescription.Parameter;
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
		public boolean isValueValid(String value, Parameter parameterDescription) {
			// unset parameters are fine
			if (value == null) {
				return true;
			}

			// Check parameter size (DOS protection)
			if (value.length() > MAX_VALUE_LENGTH) {
				return false;
			}

			// Check parameter content (R injection protection)
			if (parameterDescription.getType().isNumeric()) {

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

		@Override
		public boolean allowUncheckedParameters(ToolDescription toolDescription) {
			// we promise to handle unchecked parameters safely, see the method
			// transformVariable()
			return true;
		}
	}

	public static RParameterSecurityPolicy R_PARAMETER_SECURITY_POLICY = new RParameterSecurityPolicy();

	private static final Logger logger = LogManager.getLogger();

	private final CountDownLatch waitProcessLatch = new CountDownLatch(1);

	// injected by handler at right after creation
	private ProcessPool processPool;
	private Process process;
	private ProcessMonitor processMonitor;

	protected RCompJob() {
	}

	/**
	 * Executes the analysis.
	 * 
	 * @throws JobCancelledException
	 */
	@Override
	protected void execute() throws JobCancelledException {
		cancelCheck();
		updateState(JobState.RUNNING, "initialising R");

		List<BufferedReader> inputReaders = new ArrayList<>();

		// load work dir initialiser
		inputReaders.add(new BufferedReader(new StringReader("setwd(\"" + jobDataDir.getAbsolutePath() + "\")\n")));

		// load handler initialiser
		inputReaders.add(new BufferedReader(new StringReader(toolDescription.getInitialiser())));

		// load document versions
		String documentVersionCommand = "source(file.path(chipster.common.lib.path, \"version-utils.R\"))\n" +
				"documentR()\n";
		inputReaders.add(new BufferedReader(new StringReader(documentVersionCommand)));

		// load input parameters
		LinkedHashMap<String, fi.csc.chipster.sessiondb.model.Parameter> parameters;
		try {
			parameters = inputMessage.getParameters(R_PARAMETER_SECURITY_POLICY, toolDescription);

			// update parameters in the db, in case comp added displayNames and descriptions
			// (cli client, replay-test)
			this.setParameters(parameters);

		} catch (ParameterValidityException e) {
			this.setErrorMessage(e.getMessage()); // always has a message
			this.setOutputText(Exceptions.getStackTrace(e));
			updateState(JobState.FAILED_USER_ERROR);
			return;
		}
		for (fi.csc.chipster.sessiondb.model.Parameter param : parameters.values()) {
			String value = param.getValue();
			Parameter toolParameter = toolDescription.getParameters().get(param.getParameterId());
			String rSnippet = transformVariable(toolParameter, value);
			logger.debug("added parameter (" + rSnippet + ")");
			inputReaders.add(new BufferedReader(new StringReader(rSnippet)));
		}

		// load input script
		String script = (String) toolDescription.getImplementation();
		inputReaders.add(new BufferedReader(new StringReader(script)));

		// do this again to document all the packages loaded by the script
		// done also before the actual script, to avoid ending up with no version info
		// in case the script fails
		inputReaders.add(new BufferedReader(new StringReader(documentVersionCommand)));

		// load script finished trigger
		inputReaders.add(new BufferedReader(new StringReader("print(\"" + SCRIPT_SUCCESSFUL_STRING + "\")\n")));

		// get a process
		cancelCheck();
		logger.debug("getting a process.");

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
					inputStringBuilder.append(line);
					inputStringBuilder.append("\n");
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

		boolean finishedBeforeTimeout;
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

	@Override
	protected void postExecute() throws JobCancelledException {
		super.postExecute();
	}

	@Override
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
	private String transformVariable(Parameter param, String value) {

		// Escape strings and such
		if (!param.getType().isNumeric()) {

			if (value == null) {
				value = "None";
			} else if (JobMessageUtils.isChecked(param)) {
				value = STRING_DELIMETER + value + STRING_DELIMETER;
			} else {
				// we promised to handle this safely when we implemented the method
				// ParameterSecurityPolicy.allowUncheckedParameters()
				value = STRING_DELIMETER + ToolUtils.toUnicodeEscapes(value) + STRING_DELIMETER;
			}
		}

		// If numeric, check for empty value
		if (param.getType().isNumeric() && value.trim().isEmpty()) {
			value = "NA"; // R's constant for "not available"
		}

		// Sanitize parameter name (remove spaces)
		String name = param.getName().getID().replaceAll(" ", "_");

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
