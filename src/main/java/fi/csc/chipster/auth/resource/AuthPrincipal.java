package fi.csc.chipster.auth.resource;

import java.security.Principal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AuthPrincipal implements Principal {
	
	String username;
	private Set<String> roles;
	private String tokenKey;
	private String remoteAddress;
	private Map<String, String> details = new HashMap<>();

	public AuthPrincipal(String username, String role) {
		this(username, null, new HashSet<String>(Arrays.asList(role)));
	}
	
	public AuthPrincipal(String username, Set<String> roles) {
		this(username, null, roles);
	}

	public AuthPrincipal(String username, String tokenKey, Set<String> roles) {
		this.username = username;
		this.roles = roles;
		this.tokenKey = tokenKey;
	}

	@Override
	public String getName() {
		return username;
	}

	public Set<String> getRoles() {
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

	public void setRemoteAddress(String remoteAddr) {
		this.remoteAddress = remoteAddr;
	}
	
	public String getRemoteAddress() {
		return this.remoteAddress;
	}

	public Map<String, String> getDetails() {
		return details;
	}

	public void setDetails(Map<String, String> details) {
		this.details = details;
	}
}
