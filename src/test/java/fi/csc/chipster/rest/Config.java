package fi.csc.chipster.rest;

import java.net.URI;
import java.util.HashMap;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.glassfish.grizzly.http.server.HttpHandler;

public class Config {
	
	public Config() {
    	// show jersey logs in console
    	Logger l = Logger.getLogger(HttpHandler.class.getName());
    	l.setLevel(Level.FINE);
    	l.setUseParentHandlers(false);
    	
    	Logger c = Logger.getLogger("fi.csc.chipster");
    	c.setLevel(Level.FINE);
    	c.setUseParentHandlers(false);
    	    	    	
    	ConsoleHandler ch = new ConsoleHandler();
    	ch.setLevel(Level.ALL);
    	l.addHandler(ch);
    	c.addHandler(ch);
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
