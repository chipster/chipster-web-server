package fi.csc.chipster.comp.java;

import java.io.IOException;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.comp.CompException;
import fi.csc.chipster.comp.CompJob;
import fi.csc.chipster.comp.GenericJobMessage;
import fi.csc.chipster.comp.JobFactory;
import fi.csc.chipster.comp.ResultCallback;
import fi.csc.chipster.comp.ToolDescription;
import fi.csc.chipster.comp.ToolDescriptionGenerator;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.chipster.toolbox.runtime.Runtime;

public class JavaJobFactory implements JobFactory {

	/**
	 * Logger for this class
	 */
	private static Logger logger = LogManager.getLogger();

	private HashMap<String, String> parameters;

	public JavaJobFactory(HashMap<String, String> parameters, Config config) throws IOException {
		this.parameters = parameters;
	}

	@SuppressWarnings(value = "unchecked")
	public CompJob createCompJob(GenericJobMessage message, ToolboxTool tool, ResultCallback resultHandler,
			int jobTimeout, Job dbJob, Runtime runtime, Config config) throws CompException {
		ToolDescription description = createToolDescription(tool);

		try {
			Class<? extends Object> jobClass = (Class<? extends Object>) description.getImplementation();
			JavaCompJobBase analysisJob = (JavaCompJobBase) jobClass.getDeclaredConstructor().newInstance();
			analysisJob.construct(message, description, resultHandler, jobTimeout, config);
			return analysisJob;

		} catch (Exception e) {
			throw new RuntimeException("internal error: type " + description.getImplementation().toString()
					+ " could not be instantiated");
		}
	}

	public HashMap<String, String> getParameters() {
		return parameters;
	}

	private ToolDescription createToolDescription(ToolboxTool tool) throws CompException {

		// get the job class
		Class<? extends Object> jobClass = null;
		String className = tool.getId().substring(0, tool.getId().lastIndexOf(".java"));

		try {
			jobClass = Class.forName(className);
		} catch (ClassNotFoundException e) {
			logger.error("could not load job class: " + tool.getId());
			throw new CompException("could not load job class: " + tool.getId());
		}

		// create analysis description
		ToolDescription td;
		td = new ToolDescriptionGenerator().generate(tool.getSadlDescription());

		td.setImplementation(jobClass);
		td.setCommand("java");
		td.setSourceCode("Source code for this tool is available within Chipster source code.");

		return td;
	}

	@Override
	public boolean isDisabled() {
		return false;
	}

}
