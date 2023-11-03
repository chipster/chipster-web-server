package fi.csc.chipster.toolbox.runtime;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.comp.CompException;
import fi.csc.chipster.comp.JobFactory;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.toolbox.sadl.SADLDescription;

public class RuntimeRepository {
	
	private Logger logger = LogManager.getLogger();
	
	public static final String TOOL_DIR_R = "R";
	public static final String TOOL_DIR_PYTHON = "python";
	
	private static final String CONF_RUNTIME_COMMAND = "toolbox-runtime-command";
	private static final String CONF_RUNTIME_PARAMETERS = "toolbox-runtime-parameters";
	private static final String CONF_RUNTIME_IMAGE = "toolbox-runtime-image";
	private static final String CONF_RUNTIME_JOB_FACTORY = "toolbox-runtime-job-factory";
	private static final String CONF_RUNTIME_TOOLS_BIN_NAME = "toolbox-runtime-tools-bin-name";
	private static final String CONF_RUNTIME_TOOLS_BIN_PATH = "toolbox-runtime-tools-bin-path";
	
	private static final String CONF_DEFAULT_RUNTIME_NAME = "toolbox-default-runtime-name";
	private static final String CONF_DEFAULT_RUNTIME_MODULE = "toolbox-default-runtime-module";
	private static final String CONF_DEFAULT_RUNTIME_FILE_EXTENSION = "toolbox-default-runtime-file-extension";
	private List<Runtime> runtimes;
	private List<RuntimeMapping> runtimeMappings;
	
	public RuntimeRepository(Config config) {
		this.runtimes = this.loadRuntimes(config);
		this.runtimeMappings = this.loadRuntimeMappings(config);
	}

	private List<Runtime> loadRuntimes(Config config) {
		
		ArrayList<Runtime> runtimes = new ArrayList<>();
		
		HashSet<String> runtimeNames = new HashSet<>();
		
		// collect all runtime names, because admin can create a new runtime by changing any of these
		runtimeNames.addAll(config.getConfigEntries(CONF_RUNTIME_COMMAND + "-").keySet());
		runtimeNames.addAll(config.getConfigEntries(CONF_RUNTIME_PARAMETERS + "-").keySet());
		runtimeNames.addAll(config.getConfigEntries(CONF_RUNTIME_IMAGE + "-").keySet());
		runtimeNames.addAll(config.getConfigEntries(CONF_RUNTIME_JOB_FACTORY + "-").keySet());
		runtimeNames.addAll(config.getConfigEntries(CONF_RUNTIME_TOOLS_BIN_NAME + "-").keySet());
		runtimeNames.addAll(config.getConfigEntries(CONF_RUNTIME_TOOLS_BIN_PATH + "-").keySet());
		
		logger.info("------ loading runtimes ------");
		
		for (String runtimeName : runtimeNames) {
			Runtime runtime = new Runtime();
			
			logger.info("load runtime " + runtimeName);
			runtime.setName(runtimeName);
			runtime.setCommand(config.getString(CONF_RUNTIME_COMMAND, runtimeName));
			runtime.setParameters(config.getString(CONF_RUNTIME_PARAMETERS, runtimeName));
			runtime.setImage(config.getString(CONF_RUNTIME_IMAGE, runtimeName));
			runtime.setJobFactory(config.getString(CONF_RUNTIME_JOB_FACTORY, runtimeName));
			runtime.setToolsBinName(config.getString(CONF_RUNTIME_TOOLS_BIN_NAME, runtimeName));
			runtime.setToolsBinPath(config.getString(CONF_RUNTIME_TOOLS_BIN_PATH, runtimeName));
			
			logger.info("  command:          " + runtime.getCommand());
			logger.info("  parameters:       " + runtime.getParameters());
			logger.info("  image:            " + runtime.getImage());
			logger.info("  job factory:      " + runtime.getJobFactory());
			logger.info("  tools-bin name:   " + runtime.getToolsBinName());
			logger.info("  tools-bin path:   " + runtime.getToolsBinPath());
			
			runtimes.add(runtime);
		}
		
		return runtimes;
	}
	
	private List<RuntimeMapping> loadRuntimeMappings(Config config) {
		
		List<RuntimeMapping> mappings = new ArrayList<>();
		
		logger.info("------ loading runtime mappings ------");
	
		for (String mappingKey : config.getConfigEntries(CONF_DEFAULT_RUNTIME_NAME + "-").keySet()) {
			RuntimeMapping mapping = new RuntimeMapping();
			
			mapping.setRuntime(config.getString(CONF_DEFAULT_RUNTIME_NAME, mappingKey));
			
			boolean hasModule = config.hasKey(CONF_DEFAULT_RUNTIME_MODULE + "-" + mappingKey);
			boolean hasFileExtension = config.hasKey(CONF_DEFAULT_RUNTIME_FILE_EXTENSION + "-" + mappingKey);
			
			if (hasModule) {
				mapping.setModule(config.getString(CONF_DEFAULT_RUNTIME_MODULE, mappingKey));
			} 
			
			if (hasFileExtension) {
				// throws IllegalArgumentExeption, if neither of module or file-extension is set 
				mapping.setFileExtension(config.getString(CONF_DEFAULT_RUNTIME_FILE_EXTENSION, mappingKey));
			}
			
			if (hasModule && hasFileExtension) {
				
				logger.info("use runtime " + mapping.getRuntime() + " for module " + mapping.getModule() + " and file extension " + mapping.getFileExtension());
				
			} else if (hasModule) {
				
				logger.info("use runtime " + mapping.getRuntime() + " for module " + mapping.getModule());
				
			} else if (hasFileExtension) {
				
				logger.info("use runtime " + mapping.getRuntime() + " for file extension " + mapping.getFileExtension());				
			}
			
			if (hasModule || hasFileExtension) {
				
				mappings.add(mapping);
				
			} else {
				
				// or should we throw an exception and prevent starting?
				logger.warn("skipping runtime mapping " + mappingKey + " because no module or file extension was configured");
			}
			
		}
		
		return mappings;
	}

	public String getRuntime(SADLDescription sadlDescription, String moduleName) throws ToolLoadException {
		// get runtime from sadl or use default
		
		String runtimeName = sadlDescription.getRuntime();
		if (runtimeName == null) {
			runtimeName = getDefaultRuntime(sadlDescription.getName().getID(), moduleName);
		}
				
		return runtimeName;
	}
	
	private String getDefaultRuntime(String toolId, String moduleName) throws ToolLoadException {
		
		return this.getRuntimeMapping(toolId, moduleName).getRuntime();
	}
		
	private RuntimeMapping getRuntimeMapping(String toolId, String moduleName) throws ToolLoadException {
		
		// both module and file-extension is set		
		for (RuntimeMapping mapping : this.runtimeMappings) {
			if (mapping.getModule() != null 
					&& mapping.getModule().equals(moduleName)
					&& mapping.getFileExtension() != null
					&& toolId.endsWith(mapping.getFileExtension())) {
				return mapping;
			}
		}
		
		// only module set
		for (RuntimeMapping mapping : this.runtimeMappings) {
			if (mapping.getModule() != null 
					&& mapping.getModule().equals(moduleName)
					&& mapping.getFileExtension() == null) {
				return mapping;
			}
		}
		
		// only file-extension set
		for (RuntimeMapping mapping : this.runtimeMappings) {
			if (mapping.getModule() == null
					&& mapping.getFileExtension() != null 
					&& toolId.endsWith(mapping.getFileExtension())) {
				return mapping;
			}
		}
		
		throw new ToolLoadException("no default runtime configured for toolId: " + toolId + ", module: " + moduleName);
	}
	
	@SuppressWarnings("unused")
	private Runtime getRuntime(String runtimeName) throws ToolLoadException {
		
		for (Runtime runtime: this.runtimes) {
			if (runtime.getName().equals(runtimeName)) {
				return runtime;
			}
		}
		
		throw new ToolLoadException("runtime " + runtimeName + " not found");
	}

	public List<Runtime> getRuntimes() {
		return this.runtimes;
	}
	
	public static JobFactory getJobFactory(Runtime runtime, Config config, File workDir, String toolId) throws CompException {
		// parameters to handler
		HashMap<String, String> parameters = new HashMap<String, String>();

		// comp work dir
		parameters.put("workDir", workDir.toString());
		
		// tool dir
		parameters.put("toolDir", getToolDir(toolId));

		// parameters from config
		parameters.put("command", runtime.getCommand());
		parameters.put("commandParameters", runtime.getParameters());

		// instantiate job factory
		JobFactory jobFactory;
		try {
			jobFactory = (JobFactory)Class.forName(runtime.getJobFactory()).getConstructor(HashMap.class, Config.class).newInstance(parameters, config);
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
				| NoSuchMethodException | SecurityException | ClassNotFoundException e) {
			
			throw new CompException("instantiating JobFactory failed", e);
		}

		return jobFactory;
	}
	
	public static String getToolDir(String toolId) {
		if (toolId == null || toolId.isEmpty()) {
			throw new IllegalArgumentException("toolId is null or empty: "  + toolId);
		} else if (toolId.endsWith(".py")) {
			return TOOL_DIR_PYTHON;
		} else if (toolId.endsWith(".java")) {
			return "java";

			// add non-R stuff starting with R before this
		} else if (toolId.endsWith(".R")) {
			return TOOL_DIR_R;
		} else {
			throw new IllegalArgumentException("unknown file extension: " + toolId);
		}
	}

}
