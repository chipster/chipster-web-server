package fi.csc.chipster.auth.model;


public class Role {
	
	// roles as String constants, because that's the only thing the RolesAllowed
	// annotation accepts
	
	// special role for the first authentication step
	public static final String PASSWORD = "password";
	public static final String CLIENT = "client";
	public static final String SESSION_DB = "session-db";
	public static final String SESSION_DB_EVENTS = "session-db-events";
	public static final String SCHEDULER = "scheduler";
	public static final String COMP = "comp";
	public static final String SERVER = "server";
	public static final String AUTHENTICATION_SERVICE = "authentication-service";
	public static final String UNAUTHENTICATED = "unauthenticated";
	public static final String SERVICE_LOCATOR = "service-locator";
}