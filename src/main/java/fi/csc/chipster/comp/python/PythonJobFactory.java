package fi.csc.chipster.comp.python;

import java.io.IOException;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.comp.CompException;
import fi.csc.chipster.comp.CompJob;
import fi.csc.chipster.comp.GenericJobMessage;
import fi.csc.chipster.comp.InterpreterJobFactory;
import fi.csc.chipster.comp.ResultCallback;
import fi.csc.chipster.comp.ToolDescription;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.chipster.toolbox.runtime.Runtime;

/**
 * Handler for analysis tools written in Python.
 * 
 * @author Aleksi Kallio
 *
 */
public class PythonJobFactory extends InterpreterJobFactory {

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();	

	public PythonJobFactory(HashMap<String, String> parameters, Config config)
			throws IOException {
		super(parameters, config);
	}

	@Override
	public CompJob createCompJob(GenericJobMessage message, ToolboxTool tool, ResultCallback resultHandler,
			int jobTimeout, Job dbJob, Runtime runtime) throws CompException {
		ToolDescription description = createToolDescription(tool, dbJob, runtime);
		
		PythonCompJob analysisJob = new PythonCompJob();
		analysisJob.construct(message, description, resultHandler, jobTimeout);
		analysisJob.setProcessPool(this.processPool);
		return analysisJob;
	}

	@Override
	protected String getStringDelimeter() {
		return PythonCompJob.STRING_DELIMETER;
	}


	@Override
	protected String getVariableNameSeparator() {
		return "_";
	}
}
