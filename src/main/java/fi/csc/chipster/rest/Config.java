package fi.csc.chipster.rest;

import java.net.URI;
import java.util.HashMap;
import java.util.regex.Pattern;

import org.apache.logging.log4j.core.LoggerContext;

public class Config {
	
	@SuppressWarnings("unused")
	private LoggerContext log4jContext;

	public Config() {    	
		configureLog4j();
	}

	public static void configureLog4j() {
		
		// send everything from java.util.logging (JUL) to log4j
	    String cn = "org.apache.logging.log4j.jul.LogManager";
	    System.setProperty("java.util.logging.manager", cn);
	    // check that the JUL logging manager was successfully changed
	    java.util.logging.LogManager lm = java.util.logging.LogManager.getLogManager();
	    if (!cn.equals(lm.getClass().getName())) {
	       try {
	           ClassLoader.getSystemClassLoader().loadClass(cn);
	       } catch (ClassNotFoundException cnfe) {
	          throw new IllegalStateException("log4j-jul jar not found from the class path. Logging to  can't be configured with log4j", cnfe);
	       }
	       throw new IllegalStateException("JUL to log4j bridge couldn't be initialized, because Logger or LoggerManager was already created. Make sure the logger field of the startup class isn't static and call this mehtod before instantiating it.");
	    }
	}

	private HashMap<String, String> defaults = new HashMap<>();
	
	{
		defaults.put("service-locator", "http://{{public-ip}}:8082/servicelocator/"); 
		defaults.put("authentication-service", "http://{{public-ip}}:8081/authservice/"); // service locator has to know this to authenticate other services
		defaults.put("session-db", "http://{{public-ip}}:8080/sessiondb/"); // uri for service registration
		defaults.put("service-locator-bind", "http://{{bind-ip}}:8082/servicelocator/");
		defaults.put("session-db-bind", "http://{{bind-ip}}:8080/sessiondb/");
		defaults.put("authentication-service-bind", "http://{{bind-ip}}:8081/authservice/");
		defaults.put("tool-repository-bind", "http://{{bind-ip}}:8083/toolrepository/");
	}
	
	private HashMap<String, String> variableDefaults = new HashMap<>();
	
	{
		variableDefaults.put("public-ip", "127.0.0.1");
		variableDefaults.put("bind-ip", "0.0.0.0"); 
	}

	public String getString(String key) {
		String value = System.getenv(key);
		if (value != null) {
			return value;
		} else {
			//TODO check configuration file before returning the default
			return replaceVariables(defaults.get(key));
		}
	}

	private String replaceVariables(String template) {
		for (String variableName : variableDefaults.keySet()) {
			String variableValue = System.getenv(variableName);
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
}
