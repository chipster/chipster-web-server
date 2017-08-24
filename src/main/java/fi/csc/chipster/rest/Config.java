package fi.csc.chipster.rest;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import com.esotericsoftware.yamlbeans.YamlReader;

public class Config {
	
	private static final String URL_INT_PREFIX = "url-int-";
	private static final String URL_EXT_PREFIX = "url-ext-";
	private static final String URL_BIND_PREFIX = "url-bind-";
	private static final String URL_ADMIN_BIND_PREFIX = "url-admin-bind-";
	private static final String SERVICE_PASSWORD_PREFIX = "service-password-";
	private static final String VARIABLE_PREFIX = "variable-";
	
	public static final String DEFAULT_CONF_PATH = "conf/chipster-defaults.yaml";	
	public static final String KEY_CONF_PATH = "conf-path";	
	
	public static final String KEY_MONITORING_USERNAME = "auth-monitoring-username";
	
	public static final String KEY_SESSION_DB_NAME = "session-db-name";
	public static final String KEY_SESSION_DB_HIBERNATE_SCHEMA = "session-db-hibernate-schema";
	public static final String KEY_SESSION_DB_RESTRICT_SHARING_TO_EVERYONE = "session-db-restrict-sharing-to-everyone";
	
	public static final String KEY_WEB_SERVER_WEB_ROOT_PATH = "web-server-web-root-path";
	
	public static final String KEY_COMP_MAX_JOBS = "comp-max-jobs";
	public static final String KEY_COMP_SCHEDULE_TIMEOUT = "comp-schedule-timeout";
	public static final String KEY_COMP_OFFER_DELAY = "comp-offer-delay";
	public static final String KEY_COMP_SWEEP_WORK_DIR = "comp-sweep-work-dir";
	public static final String KEY_COMP_TIMEOUT_CHECK_INTERVAL = "comp-timeout-check-interval";
	public static final String KEY_COMP_STATUS_INTERVAL = "comp-status-interval";
	public static final String KEY_COMP_MODULE_FILTER_NAME = "comp-module-filter-name";
	public static final String KEY_COMP_MODULE_FILTER_MODE = "comp-module-filter-mode";
	public static final String KEY_COMP_RESOURCE_MONITORING_INTERVAL = "comp-resource-monitoring-interval";
	
	public static final String KEY_SCHEDULER_WAIT_TIMEOUT = "scheduler-wait-timeout";
	public static final String KEY_SCHEDULER_WAIT_RUNNABLE_TIMEOUT = "scheduler-wait-runnable-timeout";
	public static final String KEY_SCHEDULER_SCHEDULE_TIMEOUT = "scheduler-schedule-timeout";
	public static final String KEY_SCHEDULER_HEARTBEAT_LOST_TIMEOUT = "scheduler-heartbeat-lost-timeout";
	public static final String KEY_SCHEDULER_JOB_TIMER_INTERVAL = "scheduler-job-timer-interval";
	
	public static final String KEY_TOOLBOX_TOOLS_BIN_PATH = "toolbox-tools-bin-path";

	private static String confFilePath = getFromFile(DEFAULT_CONF_PATH, KEY_CONF_PATH);
	
	private static Logger logger;
	
	@SuppressWarnings("unused")
	private LoggerContext log4jContext;

	public Config() {    	
		configureLog4j();
	}

	public void configureLog4j() {
		
		// send everything from java.util.logging (JUL) to log4j
	    String cn = "org.apache.logging.log4j.jul.LogManager";
	    System.setProperty("java.util.logging.manager", cn);
	    logger = LogManager.getLogger();
	    // check that the JUL logging manager was successfully changed
	    java.util.logging.LogManager lm = java.util.logging.LogManager.getLogManager();
	    if (!cn.equals(lm.getClass().getName())) {
	       try {
	           ClassLoader.getSystemClassLoader().loadClass(cn);
	       } catch (ClassNotFoundException cnfe) {
	          logger.warn("log4j-jul jar not found from the class path. Logging to  can't be configured with log4j", cnfe);
	       }
	       logger.warn("JUL to log4j bridge couldn't be initialized, because Logger or LoggerManager was already created. Make sure the logger field of the startup class isn't static and call this mehtod before instantiating it.");
	    }
	}
	
	public static void setLoggingLevel(String logger, Level level) {
		LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
		Configuration config = ctx.getConfiguration();
		LoggerConfig loggerConfig = config.getLoggerConfig(logger); 
		loggerConfig.setLevel(level);
		ctx.updateLoggers();
	}
	
	public static Level getLoggingLevel(String logger) {
		LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
		Configuration config = ctx.getConfiguration();
		LoggerConfig loggerConfig = config.getLoggerConfig(logger); 
		return loggerConfig.getLevel();
	}		
	
	private HashMap<String, String> variableDefaults = getVariableDefaults();

	private static boolean confFileWarnShown;
	
	public String getString(String key) {
		return getString(key, true, true, true);
	}

	private HashMap<String, String> getVariableDefaults() {
		return (HashMap<String, String>) readFile(DEFAULT_CONF_PATH).entrySet().stream()
			.filter(entry -> entry.getKey().startsWith(VARIABLE_PREFIX))
			.collect(Collectors.toMap(e -> e.getKey().replace(VARIABLE_PREFIX, ""), e -> e.getValue()));
	}

	public String getString(String key, boolean env, boolean file, boolean defaultValue) {
		String value = null;
		if (env) {
			// only underscore is allowed in bash variables
			value = System.getenv(key.replace("-", "_"));
		}
		if (value == null && file) {
			value = getFromFile(confFilePath, key);
		}
		if (value == null && defaultValue) {
			value = getDefault(key);
		}
		return value;
	}
	
	private static String getFromFile(String confFilePath, String key) {
		return readFile(confFilePath).get(key);
	}
	
	private static HashMap<String, String> readFile(String confFilePath) {
		
		HashMap<String, String> conf = new HashMap<>();
		try {
			YamlReader reader = new YamlReader(new FileReader(confFilePath));
			Object object = reader.read();
			if (object instanceof Map) {
				@SuppressWarnings("rawtypes")
				Map confFileMap = (Map) object;
				
				for (Object key : confFileMap.keySet()) {
					conf.put(key.toString(), confFileMap.get(key).toString());
				}
			} else if (object == null){
				// empty config file
			} else {
				throw new RuntimeException("configuration file should be a yaml map, but it is " + object);
			}
		} catch (FileNotFoundException e) {
			// show only once per JVM
			if (!Config.confFileWarnShown) {
				logger.warn("configuration file " + confFilePath + " not found");
				Config.confFileWarnShown = true;
			}
		} catch (IOException e) {
			// convert to runtime exception, because there is no point to continue
			// (so no point to check exceptions either)
			throw new RuntimeException("failed to read the config file " + confFilePath, e);	
		}
		return conf;
	}

	/**
	 * Each service has a configuration item for the password, which is created
	 * automatically and accessed with this method. The method will log a warning if 
	 * the default password is used.
	 * 
	 * @param username
	 * @return
	 * @throws IOException 
	 */
	public String getPassword(String username) {
		String key = SERVICE_PASSWORD_PREFIX + username;
		if (isDefault(key)) {
			logger.warn("default password for username " + username);
		}		
		return getString(key);
	}
	
	/**
	 * Collect all services that have a service password entry and their configured passwords
	 * 
	 * Collecting the list of services straight from the config file makes it easier to add new services.
	 * The list of services is collected only from the default config file, because there should be no need
	 * to alter this list in configuration. Passwords have to be configurable
	 * so those are of course collected from all normal configuration locations.
	 * 
	 * @return a map where keys are the service names and values are the service passwords
	 */
	public Map<String, String> getServicePasswords() {
		
		List<String> services = readFile(DEFAULT_CONF_PATH).keySet().stream()
				.filter(confKey -> confKey.startsWith(SERVICE_PASSWORD_PREFIX))
				.map(confKey -> confKey.replace(SERVICE_PASSWORD_PREFIX, ""))
				.collect(Collectors.toList());
		
		return services.stream()
				.collect(Collectors.toMap(service -> service, service -> getPassword(service)));
	}
	
	/**
	 * Service having an internal address
	 * 
	 * @return a map where keys are the service names and values are their internal addresses 
	 */
	public Map<String, String> getInternalServiceUrls() {
		return readFile(DEFAULT_CONF_PATH).entrySet().stream()
			.filter(entry -> entry.getKey().startsWith(URL_INT_PREFIX))
			.collect(Collectors.toMap(e -> e.getKey().replace(URL_INT_PREFIX, ""), e -> getString(e.getKey())));
	}

	/**
	 * Service having an external address
	 * 
	 * @return a map where keys are the service names and values are their external addresses 
	 */
	public Map<String, String> getExternalServiceUrls() {
		return readFile(DEFAULT_CONF_PATH).entrySet().stream()
			.filter(entry -> entry.getKey().startsWith(URL_EXT_PREFIX))
			.collect(Collectors.toMap(e -> e.getKey().replace(URL_EXT_PREFIX, ""), e -> getString(e.getKey())));
	}
	
	public String getBindUrl(String service) {
		return getString(URL_BIND_PREFIX + service);
	}
	
	public String getAdminBindUrl(String service) {
		return getString(URL_ADMIN_BIND_PREFIX + service);
	}
	
	private String getDefault(String key) {
		String template = getFromFile(DEFAULT_CONF_PATH, key);
		if (template == null) {
			throw new IllegalArgumentException("configuration key not found: " + key);
		}
		return replaceVariables(template);
	}
	
	public boolean isDefault(String key) {
		return getDefault(key).equals(getString(key));
	}
	
	private String replaceVariables(String template) {
		for (String variableName : variableDefaults.keySet()) {
			// if set in environment variable
			String variableValue = System.getenv(variableName);
			// use default
			if (variableValue == null) {
				variableValue = variableDefaults.get(variableName);
			}
			template = template.replaceAll(Pattern.quote("{{" + variableName + "}}"), variableValue);
		}
		return template;
	}

	public URI getURI(String key) {
		return URI.create(getString(key));
	}

	public int getInt(String key) throws NumberFormatException {
		return Integer.parseInt(getString(key));
	}

	public boolean getBoolean(String key) {
		return "true".equalsIgnoreCase(getString(key));
	}

	public long getLong(String key) throws NumberFormatException {
		return Long.parseLong(getString(key));
	}
}
