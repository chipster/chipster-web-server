/*
 * Created on Jan 27, 2005
 *
 */
package fi.csc.chipster.comp.python;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * Uses Python to run actual analysis operations.
 * 
 * @author Aleksi Kallio
 */
public class PythonCompJob extends OnDiskCompJobBase {

	public static final String STRING_DELIMETER = "'";
	public static final String CHIPSTER_VARIABLES_FILE = "chipster_variables.py";

	public static final String ERROR_MESSAGE_TOKEN = "Traceback";
	private static final Pattern SUCCESS_STRING_PATTERN = Pattern.compile("^" + SCRIPT_SUCCESSFUL_STRING + "$");

	/**
	 * Checks that parameter values are safe to insert into Python code. Should
	 * closely match the code that is used to output the values in
	 * transformVariable(...).
	 * 
	 * @see PythonCompJob#transformVariable(ParameterDescription, String)
	 *
	 */
	public static class PythonParameterSecurityPolicy extends ParameterSecurityPolicy {

		private static final int MAX_VALUE_LENGTH = 1000;

		/**
		 * This regular expression is very critical, because it checks code that is
		 * directly inserted into Python script. Hence it should be very conservative.
		 * 
		 * Interpretation: Maybe minus, zero or more digits, maybe point, zero or more
		 * digits.
		 */
		public static String NUMERIC_VALUE_PATTERN = "-?\\d*\\.?\\d*";

		/**
		 * This regular expression is not very critical, because text is inserted inside
		 * string constant in Python code. It should however always be combined with
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

			// Check parameter content (Python injection protection)
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

	public static PythonParameterSecurityPolicy PARAMETER_SECURITY_POLICY = new PythonParameterSecurityPolicy();

	private static final Logger logger = LogManager.getLogger();

	private final CountDownLatch waitProcessLatch = new CountDownLatch(1);

	// injected by handler at right after creation
	private ProcessPool processPool;
	private Process process;
	private ProcessMonitor processMonitor;

	protected PythonCompJob() {
	}

	/**
	 * Executes the analysis.
	 * 
	 * @throws JobCancelledException
	 */
	@Override
	protected void execute() throws JobCancelledException {

		logger.debug("PythonCompJob.execute()");

		cancelCheck();

		updateState(JobState.RUNNING, "initialising Python");

		List<BufferedReader> inputReaders = new ArrayList<>();

		// load handler initialiser
		inputReaders.add(new BufferedReader(new StringReader(toolDescription.getInitialiser())));

		// load work dir initialiser
		logger.debug("job dir: " + jobDir.getPath());
		String importOs = "import os\n";
		String chDir = "os.chdir('" + jobDataDir.getAbsolutePath() + "')\n";
		String importSys = "import sys\n";

		// possibly needs to be absolute because "__main__ script cannot use relative
		// imports"
		String appendSysPath = "sys.path.append(os.path.join(os.getcwd(), chipster_common_lib_path))\n";

		// write chipster variables to a file so that they can be imported in python lib
		// files
		// such as version_utils.py
		Path variablesFilePath = new File(jobDataDir, CHIPSTER_VARIABLES_FILE).toPath();

		try {
			Files.write(variablesFilePath, toolDescription.getInitialiser().getBytes(), StandardOpenOption.CREATE);

		} catch (IOException e) {
			this.setErrorMessage("Writing variables file failed");
			this.setOutputText(Exceptions.getStackTrace(e));
			updateState(JobState.ERROR);
			return;
		}

		String importVersionUtils = "import version_utils\n";
		String documentVersions = "version_utils.document_python_version()\n";

		// String importVersionUtils = "from version_utils import *\n";
		// String documentVersions = "document_python_version()\n";

		inputReaders.add(new BufferedReader(new StringReader(importOs + chDir + importSys + appendSysPath
				+ importVersionUtils + documentVersions)));

		// load input parameters
		LinkedHashMap<String, fi.csc.chipster.sessiondb.model.Parameter> parameters;
		try {
			parameters = inputMessage.getParameters(PARAMETER_SECURITY_POLICY, toolDescription);

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
			String pythonSnippet = transformVariable(toolParameter, value);
			logger.debug("added parameter (" + pythonSnippet + ")");
			inputReaders.add(new BufferedReader(new StringReader(pythonSnippet)));
		}

		// load input script
		String script = (String) toolDescription.getImplementation();
		inputReaders.add(new BufferedReader(new StringReader(script)));

		// load script finished trigger
		inputReaders.add(new BufferedReader(new StringReader("print()\nprint('" + SCRIPT_SUCCESSFUL_STRING + "')\n")));

		// get a process
		cancelCheck();
		logger.debug("getting a process.");
		try {
			this.process = processPool.getProcess();
		} catch (Exception e) {
			this.setErrorMessage("Starting Python failed.");
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
			this.setErrorMessage("Starting Python failed.");
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
			this.setOutputText("Python already finished.\n\n" + output);
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
			logger.warn("creating Python input failed");
		}

		this.setSourceCode(inputStringBuilder.toString());

		updateState(JobState.RUNNING, "running Python");

		// launch the process monitor
		cancelCheck();
		processMonitor = new ProcessMonitor(process, (screenOutput) -> onScreenOutputUpdate(screenOutput),
				(jobState, screenOutput) -> jobFinished(jobState, "", screenOutput), SUCCESS_STRING_PATTERN);

		new Thread(processMonitor).start();

		// write the input to process
		BufferedWriter writer = null;
		try {
			writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
			writer.write(inputStringBuilder.toString());
			writer.newLine();
			writer.flush();
		} catch (IOException ioe) {
			// this happens if Python interpreter has died before or dies while writing the
			// input
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
	 * Converts a name-value -pair into Python variable definition.
	 * 
	 * @param param
	 * @param value
	 * @return String
	 */
	public static String transformVariable(Parameter param, String value) {

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
			value = "float('NaN')";
		}

		// Sanitize parameter name (remove spaces)
		String name = param.getName().getID();
		name = name.replaceAll(" ", "_");

		// Construct and return parameter assignment
		return (name + " = " + value);
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
			updateState(JobState.RUNNING, "running Python");
		}
	}

	private synchronized void jobFinished(JobState state, String stateDetails, String screenOutput) {
		try {

			// check if job already finished for some reason like cancel
			if (this.getState().isFinished()) {
				return;
			}

			// add output to result message
			this.setOutputText(screenOutput);

			// for a failed job, get error message from screen output
			boolean noteFound = false;
			if (state == JobState.FAILED) {
				String errorMessage = getErrorMessage(screenOutput, ERROR_MESSAGE_TOKEN, null);

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
					this.setErrorMessage("Running Python script failed.");
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
