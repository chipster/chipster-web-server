package fi.csc.chipster.auth.model;
public class Role {
	
	// roles as String constants, because that's the only thing the RolesAllowed
	// annotation accepts
	
	// special role for the first authentication step
	public static final String PASSWORD = "PASSWORD";
	public static final String CLIENT = "CLIENT";
	public static final String SESSION_STORAGE = "SESSION_STORAGE";
	public static final String SERVER = "SERVER";
}