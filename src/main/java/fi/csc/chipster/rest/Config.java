package fi.csc.chipster.rest;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import com.esotericsoftware.yamlbeans.YamlReader;

public class Config {
	
	private static final String KEY_CONF_PATH = "conf-path";
	
	public static final String KEY_TOOLBOX_URL = "toolbox-url";
	public static final String KEY_TOOLBOX_PUBLIC_URL = "toolbox-url-pub";
	public static final String KEY_TOOLBOX_BIND_URL = "toolbox-bind-url";
	public static final String KEY_COMP_MAX_JOBS = "comp-max-jobs";
	public static final String KEY_COMP_SCHEDULE_TIMEOUT = "comp-schedule-timeout";
	public static final String KEY_COMP_OFFER_DELAY = "comp-offer-delay";
	public static final String KEY_COMP_SWEEP_WORK_DIR = "comp-sweep-work-dir";
	public static final String KEY_COMP_TIMEOUT_CHECK_INTERVAL = "comp-timeout-check-interval";
	public static final String KEY_COMP_JOB_HEARTBEAT_INTERVAL = "comp-job-heartbeat-interval";
	public static final String KEY_COMP_AVAILABLE_INTERVAL = "comp-available-interval";	
	public static final String KEY_TOOLS_BIN_PATH = "tools-bin-path";
	
	public static final String USERNAME_SESSION_DB = "session-db";
	public static final String USERNAME_SERVICE_LOCATOR = "service-locator";
	public static final String USERNAME_SCHEDULER = "scheduler";
	public static final String USERNAME_COMP = "comp";
	public static final String USERNAME_FILE_BROKER = "file-broker";
	public static final String USERNAME_SESSION_WORKER = "session-worker";
	public static final String USERNAME_PROXY = "proxy";
	
	public static final List<String> services = Arrays.asList(new String[] {
		USERNAME_SESSION_DB,
		USERNAME_SERVICE_LOCATOR,
		USERNAME_SCHEDULER,
		USERNAME_COMP,
		USERNAME_FILE_BROKER,
		USERNAME_SESSION_WORKER,
		USERNAME_PROXY
	});

	
	// create a password configuration key for each service 
	private static Map<String, String> serviceAccounts = new HashMap<>();
	{
		for (String username : services) {
			serviceAccounts.put(username, username + "-password");
		}
	}
	
	private Logger logger;
	
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

	private HashMap<String, String> defaults = new HashMap<>();
	
	{
		// uri for service registration
		defaults.put("web", 						"http://{{public-ip}}:8000");
		defaults.put("authentication-service", 		"http://{{public-ip}}:8002");
		defaults.put("authentication-service-pub", 	"http://{{public-ip}}:8002");
		defaults.put("service-locator", 			"http://{{public-ip}}:8003"); 
		defaults.put("session-db", 					"http://{{public-ip}}:8004");
		defaults.put("session-db-events", 			"ws://{{public-ip}}:8005");
		defaults.put("session-db-pub", 				"http://{{public-ip}}:8004");
		defaults.put("session-db-events-pub",		"ws://{{public-ip}}:8005");
		defaults.put("scheduler", 					"ws://{{public-ip}}:8006");
		defaults.put("file-broker", 				"http://{{public-ip}}:8007");
		defaults.put("file-broker-pub",				"http://{{public-ip}}:8007");
        defaults.put(KEY_TOOLBOX_URL, 				"http://{{public-ip}}:8008");
        defaults.put(KEY_TOOLBOX_PUBLIC_URL, 		"http://{{public-ip}}:8008");
        defaults.put("session-worker", 				"http://{{public-ip}}:8009");
        defaults.put("session-worker-pub", 			"http://{{public-ip}}:8009");
		
        // uri for the server to bind
        defaults.put("web-bind", 					"http://{{bind-ip}}:8000");
        defaults.put("proxy-bind", 					"http://{{bind-ip}}:8001");
        defaults.put("proxy-admin-bind", 			"http://{{admin-bind-ip}}:9001");
        defaults.put("authentication-service-bind", "http://{{bind-ip}}:8002");
		defaults.put("service-locator-bind", 		"http://{{bind-ip}}:8003");
		defaults.put("session-db-bind", 			"http://{{bind-ip}}:8004");
		defaults.put("session-db-events-bind", 		"ws://{{bind-ip}}:8005");
		defaults.put("scheduler-bind", 				"ws://{{bind-ip}}:8006");
		defaults.put("file-broker-bind", 			"http://{{bind-ip}}:8007");
		defaults.put(KEY_TOOLBOX_BIND_URL, 			"http://{{bind-ip}}:8008");
		defaults.put("session-worker-bind", 		"http://{{bind-ip}}:8009");
		
		defaults.put(KEY_COMP_MAX_JOBS,								"" + Integer.MAX_VALUE); // max number of jobs run simultaneusly
		defaults.put(KEY_COMP_SCHEDULE_TIMEOUT, 					"10"); // time after which a scheuduled job is removed if there is no reponse from the scheduler
		defaults.put(KEY_COMP_OFFER_DELAY, 							"100"); // delay before sending the job offer message, multiplied by number of scheduled jobs, milliseconds
		defaults.put(KEY_COMP_SWEEP_WORK_DIR,						"true"); // should job specific temporary directory be sweeped after job execution
		defaults.put(KEY_COMP_TIMEOUT_CHECK_INTERVAL, 				"1000"); // schedule timeout check interval, milliseconds
		defaults.put(KEY_COMP_JOB_HEARTBEAT_INTERVAL, 				"15000"); // job heartbeat interval, milliseconds
		defaults.put(KEY_COMP_AVAILABLE_INTERVAL,					"60000"); // send comp available frequency, milliseconds
		
		defaults.put("session-db-replicate", "false");
		defaults.put("session-db-name", "session-db");
		defaults.put("session-db-hibernate-schema", "update"); // update, validate or create
		
		defaults.put("web-root-path", "../chipster-web/");
		defaults.put(KEY_CONF_PATH, "conf/chipster.yaml");
		defaults.put(KEY_TOOLS_BIN_PATH, "/opt/chipster/tools");
		
		// service credentials
		for (Entry<String, String> entry : serviceAccounts.entrySet()) {
			String username = entry.getKey();
			String passwordConfigKey = entry.getValue();
			// default password is the same as username;
			defaults.put(passwordConfigKey, username);
		}	
	}
	
	private HashMap<String, String> variables = new HashMap<>();
	private HashMap<String, String> variableDefaults = new HashMap<>();

	private static boolean conFFileWarnShown;
	
	{
		variableDefaults.put("public-ip", "127.0.0.1");
		variableDefaults.put("bind-ip", "0.0.0.0"); 
		variableDefaults.put("admin-bind-ip", "127.0.0.1");
	}
	
	public String getString(String key) throws IOException {
		return getString(key, true, true, true);
	}

	public String getString(String key, boolean env, boolean file, boolean defaultValue) throws IOException {
		String value = null;
		if (env) {
			// only underscore is allowed in bash variables
			value = System.getenv(key.replace("-", "_"));
		}
		if (value == null && file) {
			value = getFromFile(key);
		}
		if (value == null && defaultValue) {
			value = getDefault(key);
		}
		return value;
	}
	
	private String getFromFile(String key) throws IOException {
		String confFilePath = getString(KEY_CONF_PATH, true, false, true);
		try {
			// don't try to find conf path from the file, because that would create a stack overflow
			YamlReader reader = new YamlReader(new FileReader(confFilePath));
			Object object = reader.read();
			if (object instanceof Map) {
				@SuppressWarnings("rawtypes")
				Map confFileMap = (Map) object;
				Object valueObj = confFileMap.get(key);
				if (valueObj instanceof String) {
					return (String)valueObj;
				}
			} else if (object == null){
				// empty config file
			} else {
				throw new RuntimeException("configuration file should be a yaml map, but it is " + object);
			}
		} catch (FileNotFoundException e) {
			// show only once per JVM
			if (!Config.conFFileWarnShown) {
				logger.warn("configuration file " + confFilePath + " not found, using environment variables or defaults");
				Config.conFFileWarnShown = true;
			}
		}
		return null;
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
	public String getPassword(String username) throws IOException {
		String key = serviceAccounts.get(username);
		if (isDefault(key)) {
			logger.warn("default password for username " + username);
		}		
		return getString(key);
	}

	private String getDefault(String key) {
		String template = defaults.get(key);
		if (template == null) {
			throw new IllegalArgumentException("configuration key not found: " + key);
		}
		return replaceVariables(template);
	}
	
	public boolean isDefault(String key) throws IOException {
		return getDefault(key).equals(getString(key));
	}
	
	public void setVariable(String key, String value) {
		variables.put(key, value);
	}

	private String replaceVariables(String template) {
		for (String variableName : variableDefaults.keySet()) {
			// if set programmatically
			String variableValue = variables.get(variableName);
			// if set in environment variable
			if (variableValue == null) {
				variableValue = System.getenv(variableName);
			}
			// use default
			if (variableValue == null) {
				variableValue = variableDefaults.get(variableName);
			}
			template = template.replaceAll(Pattern.quote("{{" + variableName + "}}"), variableValue);
		}
		return template;
	}

	public URI getURI(String key) throws IOException {
		return URI.create(getString(key));
	}

	public int getInt(String key) throws NumberFormatException, IOException {
		return Integer.parseInt(getString(key));
	}

	public boolean getBoolean(String key) throws IOException {
		return "true".equalsIgnoreCase(getString(key));
	}

	public void set(String key, String value) {
		this.defaults.put(key, value);
	}
}
