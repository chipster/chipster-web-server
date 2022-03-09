package fi.csc.chipster.auth.resource;

import java.security.Principal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import fi.csc.chipster.auth.model.ChipsterToken;
import fi.csc.chipster.auth.model.UserToken;

/**
 * Wrapper for Chipster tokens to be passed through Java authentication APIs
 * 
 * @author klemela
 *
 */
public class AuthPrincipal implements Principal {
	
	// are remoteAddress and details used anymore?
	private String remoteAddress;
	private Map<String, String> details = new HashMap<>();
	private ChipsterToken token;
	private String tokenKey;

	public AuthPrincipal(String role) {
		this(null, null, new HashSet<String>(Arrays.asList(role)));
	}
	
	public AuthPrincipal(String username, Set<String> roles) {
		this(username, null, roles);
	}
	
	public AuthPrincipal(String username, String tokenKey, Set<String> roles) {
		
		this(new UserToken(username, null, null, roles), tokenKey);
	}

	public AuthPrincipal(ChipsterToken token, String tokenKey) {
		
		this.token = token;
		this.tokenKey = tokenKey;
	}

	@Override
	public String getName() {
		return token.getUsername();
	}

	public Set<String> getRoles() {
		return token.getRoles();
	}

	public String getTokenKey() {
		return tokenKey;
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

	public ChipsterToken getToken() {
		return token;
	}
}
