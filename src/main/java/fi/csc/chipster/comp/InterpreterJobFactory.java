package fi.csc.chipster.comp;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.toolbox.ToolboxTool;

/**
 * Abstract base class for any JobFactory that connects to external interpreter to run commands.
 *  
 * @author Aleksi Kallio
 *
 */
public abstract class InterpreterJobFactory implements JobFactory {
	
	/**
	 * Logger for this class
	 */
	private static Logger logger = LogManager.getLogger();

	protected String interpreterCommand;
	protected String toolDir;
	protected ProcessPool processPool;
	protected boolean isDisabled = false;

	private Config config;


	public InterpreterJobFactory(HashMap<String, String> parameters, Config config) throws IOException {
		
		this.config = config;
		
		String command = parameters.get("command");
		if (command == null || command.equals("")) {
			throw new IllegalArgumentException("Illegal command string: " + command);
		}
		
		String commandParameters = parameters.get("commandParameters");
		
		if (commandParameters != null) {
			command += " " + commandParameters;
		}
		this.interpreterCommand = command;
		this.toolDir = parameters.get("toolDir");
			
		try {
			processPool = new ProcessPool(new File(parameters.get("workDir")), interpreterCommand);
		} catch (Exception e) {
			logger.warn("disabling handler " + this.getClass().getSimpleName() + ": " + e.getMessage());
			this.isDisabled = true;
		}
	}

	@Override
	public abstract CompJob createCompJob(GenericJobMessage message, ToolboxTool tool, 
			ResultCallback resultHandler, int jobTimeout) throws CompException;

	protected abstract String getStringDelimeter();
	protected abstract String getVariableNameSeparator();

	protected ToolDescription createToolDescription(ToolboxTool tool) throws CompException {

		File moduleDir = new File(tool.getModule());
		
		// create description
		ToolDescription ad;
		ad = new ToolDescriptionGenerator().generate(tool.getSadlDescription());

		// add interpreter specific stuff to ToolDescription
		ad.setCommand(interpreterCommand);
		ad.setImplementation(tool.getSource()); // include headers
		ad.setSourceCode(tool.getSource());

		// tool and script locations and other variables
		int threadsMax = config.getInt("comp-job-threads-max");

		int memoryMax = config.getInt("comp-job-memory-max");		

		// toolbox tools dir relative to job data dir
		File toolsRootDir = new File("../toolbox/tools");
		File commonScriptDir = new File(toolsRootDir, "common/" + toolDir);
		File relativeModuleDir = new File(toolsRootDir, moduleDir.getName());
		
		File chipsterRootDir;
		try {
			chipsterRootDir = new File("../").getCanonicalFile();
		} catch (IOException e) {
			logger.warn("failed to get base dir, using default");
			chipsterRootDir = new File("/opt/chipster");
		}

		File javaLibsDir = new File(chipsterRootDir, "shared/lib");
		File externalToolsDir = new File(chipsterRootDir, "tools");
		
		String vns = getVariableNameSeparator();
		String sd = getStringDelimeter();
		
		ad.setInitialiser(
				"chipster" + vns + "tools" + vns + "path = " + sd + externalToolsDir + sd + "\n" +
				"chipster" + vns + "common" + vns + "path = " + sd + commonScriptDir + sd + "\n" + 
				"chipster" + vns + "module" + vns + "path = " + sd + relativeModuleDir + sd + "\n" + 
				"chipster" + vns + "java" + vns + "libs" + vns + "path = " + sd + javaLibsDir + sd + "\n" + 
				"chipster" + vns + "threads" + vns + "max = " + sd + threadsMax + sd + "\n" +
				"chipster" + vns + "memory" + vns + "max = " + sd + memoryMax + sd + "\n");

		return ad;		
	}

	
	public boolean isDisabled() {
		return this.isDisabled;
	}

}
