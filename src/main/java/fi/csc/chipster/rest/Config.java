package fi.csc.chipster.rest;

import java.net.URI;
import java.util.HashMap;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;

public class Config {
	
	@SuppressWarnings("unused")
	private LoggerContext log4jContext;

	public Config() {    	
		configureLog4J();
	}

	private void configureLog4J() {
		// send everything from java.util.logging to log4j
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
		
		// configure logging levels using log4j
		Configurator.setRootLevel(Level.INFO);
		Configurator.setLevel("org.glassfish.grizzly", Level.WARN);
		Configurator.setLevel("org.glassfish.jersey.filter.LoggingFilter", Level.INFO);
		Configurator.setLevel("com.mchange.v2", Level.WARN);
		Configurator.setLevel("com.mchange.v2.c3p0", Level.WARN);
		Configurator.setLevel("org.hibernate", Level.WARN);
		Configurator.setLevel("fi.csc.chipster", Level.INFO);
	}

	private HashMap<String, String> defaults = new HashMap<>();
	
	{
		defaults.put("service-locator", "http://{{public-ip}}:8082/servicelocator/"); 
		defaults.put("authentication-service", "http://{{public-ip}}:8081/authservice/"); // service locator has to know this to authenticate other services
		defaults.put("session-storage", "http://{{public-ip}}:8080/sessionstorage/"); // uri for service registration
		defaults.put("service-locator-bind", "http://{{bind-ip}}:8082/servicelocator/");
		defaults.put("session-storage-bind", "http://{{bind-ip}}:8080/sessionstorage/");
		defaults.put("authentication-service-bind", "http://{{bind-ip}}:8081/authservice/");
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
