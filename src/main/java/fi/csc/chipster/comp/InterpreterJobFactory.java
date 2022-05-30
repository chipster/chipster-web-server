package fi.csc.chipster.comp;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.scheduler.bash.BashJobScheduler;
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

	public static final String CONF_CHIPSTER_ROOT_DIR = "comp-chipster-root-dir";
	
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

		// calculate variables for max threads and memory
		Integer slots = tool.getSadlDescription().getSlotCount();
		
		if (slots == null) {
			slots = 1;
		}
		
		// if the default value is changed, these variables have to be configured both for scheduler and comp
		// configuration is in GiB, variable in MiB		
		int memoryMax = BashJobScheduler.getMemoryLimit(slots, config) * 1024;
		int threadsMax = BashJobScheduler.getCpuLimit(slots, config);
		
		// tool and script locations and other variables
		// toolbox tools dir relative to job data dir
		File toolsRootDir = new File("../toolbox/tools");
		File commonScriptDir = new File(toolsRootDir, "common/" + toolDir);
		File relativeModuleDir = new File(toolsRootDir, moduleDir.getName());
		
		File chipsterRootDir;
		try {
			String configChipsterRootDir = config.getString(CONF_CHIPSTER_ROOT_DIR);
			chipsterRootDir = new File(configChipsterRootDir).getCanonicalFile();
		} catch (IOException e) {
			logger.warn("failed to get base dir, using default");
			chipsterRootDir = new File("/opt/chipster");
		}

		File javaLibsDir = new File(chipsterRootDir, "lib");
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
