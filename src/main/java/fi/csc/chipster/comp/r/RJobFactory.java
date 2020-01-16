package fi.csc.chipster.comp.r;

import java.io.IOException;
import java.util.HashMap;

import org.apache.log4j.Logger;

import fi.csc.chipster.comp.CompException;
import fi.csc.chipster.comp.CompJob;
import fi.csc.chipster.comp.GenericJobMessage;
import fi.csc.chipster.comp.InterpreterJobFactory;
import fi.csc.chipster.comp.ResultCallback;
import fi.csc.chipster.comp.ToolDescription;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.toolbox.ToolboxTool;

public class RJobFactory extends InterpreterJobFactory {

	static final Logger logger = Logger
			.getLogger(RJobFactory.class);


	public RJobFactory(HashMap<String, String> parameters, Config config)
			throws IOException {
		super(parameters, config);
	}

	@Override
	public CompJob createCompJob(GenericJobMessage message, ToolboxTool tool, ResultCallback resultHandler,
			int jobTimeout) throws CompException {

		ToolDescription description = createToolDescription(tool);
		
		RCompJob analysisJob = new RCompJob();
		analysisJob.construct(message, description, resultHandler, jobTimeout);
		analysisJob.setProcessPool(this.processPool);
		return analysisJob;
	}

	@Override
	protected String getStringDelimeter() {
		return RCompJob.STRING_DELIMETER;
	}

	@Override
	protected String getVariableNameSeparator() {
		return ".";
	}

}
