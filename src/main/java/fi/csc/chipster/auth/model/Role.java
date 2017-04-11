package fi.csc.chipster.auth.model;

public class Role {
	
	// roles as String constants	because that's the only thing the RolesAllowed
	// annotation accepts
	
	// special role for the first authentication step
	public static final String PASSWORD = "password";
	public static final String CLIENT = "client";
	public static final String SESSION_DB = "session-db";
	public static final String SESSION_DB_EVENTS = "session-db-events";
	public static final String SCHEDULER = "scheduler";
	public static final String COMP = "comp";
	public static final String SERVER = "server";
	public static final String AUTH = "auth";
	public static final String UNAUTHENTICATED = "unauthenticated";
	public static final String SERVICE_LOCATOR = "service-locator";
	public static final String FILE_BROKER = "file-broker";
	public static final String TOOLBOX = "toolbox";
	public static final String ADMIN = "admin";
	public static final String PROXY = "proxy";
	public static final String SESSION_WORKER = "session-worker";
	public static final String TYPE_SERVICE = "type-service";
	public static final String WEB_SERVER = "web-server";
	public static final String PROXY_ADMIN = "proxy-admin";
}	
