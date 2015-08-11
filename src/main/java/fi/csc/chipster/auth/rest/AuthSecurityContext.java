package fi.csc.chipster.auth.rest;

import java.security.Principal;
import java.util.Arrays;
import java.util.HashSet;

import javax.ws.rs.core.SecurityContext;

import fi.csc.chipster.auth.model.Role;

public class AuthSecurityContext implements SecurityContext {

	private String username;
	private HashSet<Role> roles;
	private SecurityContext originalContext;

	public AuthSecurityContext(String username, SecurityContext originalContext, Role... roles) {
		this.username = username;
		this.roles = new HashSet<>(Arrays.asList(roles));
		this.originalContext = originalContext;
	}

	@Override
	public Principal getUserPrincipal() {
		return new AuthPrincipal(username);
	}

	@Override
	public boolean isUserInRole(String role) {
		return roles.contains(Role.valueOf(role));
	}

	@Override
	public boolean isSecure() {
		return originalContext.isSecure();
	}

	@Override
	public String getAuthenticationScheme() {
		return originalContext.getAuthenticationScheme();
	}

}
