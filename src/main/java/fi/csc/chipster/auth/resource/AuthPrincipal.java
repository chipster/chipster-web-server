package fi.csc.chipster.auth.resource;

import java.security.Principal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import fi.csc.chipster.sessiondb.model.SessionDbToken;

public class AuthPrincipal implements Principal {
	
	String username;
	private Set<String> roles;
	private String tokenKey;
	private String remoteAddress;
	private Map<String, String> details = new HashMap<>();
	private SessionDbToken sessionDbToken;

	public AuthPrincipal(String username, String role) {
		this(username, null, new HashSet<String>(Arrays.asList(role)), null);
	}
	
	public AuthPrincipal(String username, Set<String> roles) {
		this(username, null, roles, null);
	}
	
	public AuthPrincipal(String username, String tokenKey, Set<String> roles) {
		this(username, tokenKey, roles, null);
	}

	public AuthPrincipal(String username, String tokenKey, Set<String> roles, SessionDbToken sessionDbToken) {
		this.username = username;
		this.roles = roles;
		this.tokenKey = tokenKey;
		this.sessionDbToken = sessionDbToken;
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

	public SessionDbToken getSessionDbToken() {
		return sessionDbToken;
	}

	public void setSessionDbToken(SessionDbToken sessionDbToken) {
		this.sessionDbToken = sessionDbToken;
	}
}
