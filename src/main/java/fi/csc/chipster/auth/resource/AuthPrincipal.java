package fi.csc.chipster.auth.resource;

import java.security.Principal;
import java.util.Arrays;
import java.util.HashSet;

public class AuthPrincipal implements Principal {
	
	String username;
	private HashSet<String> roles;
	private String tokenKey;

	public AuthPrincipal(String username, String role) {
		this(username, null, new HashSet<String>(Arrays.asList(role)));
	}
	
	public AuthPrincipal(String username, HashSet<String> roles) {
		this(username, null, roles);
	}

	public AuthPrincipal(String username, String tokenKey, HashSet<String> roles) {
		this.username = username;
		this.roles = roles;
		this.tokenKey = tokenKey;
	}

	@Override
	public String getName() {
		return username;
	}

	public HashSet<String> getRoles() {
		return roles;
	}

	public void setRoles(HashSet<String> roles) {
		this.roles = roles;
	}

	public String getTokenKey() {
		return tokenKey;
	}

	public void setTokenKey(String tokenKey) {
		this.tokenKey = tokenKey;
	}
}
