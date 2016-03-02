package fi.csc.chipster.rest;

import java.net.URI;
import java.util.HashMap;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

public class Config {
	
	public static final String KEY_TOOLBOX_USERNAME = "toolbox-username";
	public static final String KEY_TOOLBOX_PASSWORD = "toolbox-password";
	public static final String KEY_TOOLBOX_URL = "toolbox-url";
	public static final String KEY_TOOLBOX_BIND_URL = "toolbox-bind-url";
	public static final String KEY_COMP_MAX_JOBS = "comp-max-jobs";
	public static final String KEY_COMP_SCHEDULE_TIMEOUT = "comp-schedule-timeout";
	public static final String KEY_COMP_OFFER_DELAY = "comp-offer-delay";
	public static final String KEY_COMP_SWEEP_WORK_DIR = "comp-sqeep-work-dir";
	public static final String KEY_COMP_TIMEOUT_CHECK_INTERVAL = "comp-timeout-check-interval";
	public static final String KEY_COMP_JOB_HEARTBEAT_INTERVAL = "comp-job-heartbeat-interval";
	public static final String KEY_COMP_AVAILABLE_INTERVAL = "comp-available-interval";	
	
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
		defaults.put("web", 						"http://{{public-ip}}:8000/");
		defaults.put("authentication-service", 		"http://{{public-ip}}:8002/authservice/"); // service locator has to know this to authenticate other services
		defaults.put("service-locator", 			"http://{{public-ip}}:8003/servicelocator/"); 
		defaults.put("session-db", 					"http://{{public-ip}}:8004/sessiondb/");
		defaults.put("session-db-events", 			"ws://{{public-ip}}:8005/sessiondbevents/");
		defaults.put("scheduler", 					"ws://{{public-ip}}:8006/scheduler/");
		defaults.put("file-broker", 				"http://{{public-ip}}:8007/filebroker/");
        defaults.put(KEY_TOOLBOX_URL, 				"http://{{public-ip}}:8008/toolbox");
		
        // uri for the server to bind
        defaults.put("web-bind", 					"http://{{bind-ip}}:8000/");
        defaults.put("proxy-bind", 					"http://{{bind-ip}}:8001/");
        defaults.put("proxy-admin-bind", 			"http://{{admin-bind-ip}}:9001/");
        defaults.put("authentication-service-bind", "http://{{bind-ip}}:8002/authservice/");
		defaults.put("service-locator-bind", 		"http://{{bind-ip}}:8003/servicelocator/");
		defaults.put("session-db-bind", 			"http://{{bind-ip}}:8004/sessiondb/");
		defaults.put("session-db-events-bind", 		"ws://{{bind-ip}}:8005/sessiondbevents/");
		defaults.put("scheduler-bind", 				"ws://{{bind-ip}}:8006/scheduler/");
		defaults.put("file-broker-bind", 			"http://{{bind-ip}}:8007/filebroker/");
		defaults.put(KEY_TOOLBOX_BIND_URL, 			"http://{{bind-ip}}:8008/toolbox/");

		defaults.put(KEY_TOOLBOX_USERNAME, 			"toolbox");
		defaults.put(KEY_TOOLBOX_PASSWORD, 			"toolboxPassword");
		
		defaults.put(KEY_COMP_MAX_JOBS,								"" + Integer.MAX_VALUE); // max number of jobs run simultaneusly
		defaults.put(KEY_COMP_SCHEDULE_TIMEOUT, 					"10"); // time after which a scheuduled job is removed if there is no reponse from the scheduler
		defaults.put(KEY_COMP_OFFER_DELAY, 							"100"); // delay before sending the job offer message, multiplied by number of scheduled jobs, milliseconds
		defaults.put(KEY_COMP_SWEEP_WORK_DIR,						"false"); // should job specific temporary directory be sweeped after job execution
		defaults.put(KEY_COMP_TIMEOUT_CHECK_INTERVAL, 				"1000"); // schedule timeout check interval, milliseconds
		defaults.put(KEY_COMP_JOB_HEARTBEAT_INTERVAL, 				"15000"); // job heartbeat interval, milliseconds
		defaults.put(KEY_COMP_AVAILABLE_INTERVAL,					"60000"); // send comp available frequency, milliseconds
		
		defaults.put("session-db-replicate", "false");
		defaults.put("session-db-name", "session-db");
		defaults.put("session-db-hibernate-schema", "update"); // update, validate or create
		
		defaults.put("web-root-path", "../chipster-web/");
	}
	
	private HashMap<String, String> variables = new HashMap<>();
	private HashMap<String, String> variableDefaults = new HashMap<>();
	
	{
		variableDefaults.put("public-ip", "127.0.0.1");
		variableDefaults.put("bind-ip", "0.0.0.0"); 
		variableDefaults.put("admin-bind-ip", "127.0.0.1");
	}

	public String getString(String key) {
		String value = System.getenv(key);
		if (value != null) {
			return value;
		} else {
			//TODO check configuration file before returning the default
			String template = defaults.get(key);
			if (template == null) {
				throw new IllegalArgumentException("configuration key not found: " + key);
			}
			return replaceVariables(template);
		}
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

	public URI getURI(String key) {
		return URI.create(getString(key));
	}

	public int getInt(String key) {
		return Integer.parseInt(getString(key));
	}

	public boolean getBoolean(String key) {
		return "true".equalsIgnoreCase(getString(key));
	}

	public void set(String key, String value) {
		this.defaults.put(key, value);
	}
}
