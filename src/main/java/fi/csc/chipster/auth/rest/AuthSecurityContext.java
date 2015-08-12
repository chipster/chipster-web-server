package fi.csc.chipster.auth.rest;

import java.security.Principal;

import javax.ws.rs.core.SecurityContext;

public class AuthSecurityContext implements SecurityContext {

	private SecurityContext originalContext;
	private AuthPrincipal principal;

	public AuthSecurityContext(AuthPrincipal principal, SecurityContext originalContext) {
		this.principal = principal;
		this.originalContext = originalContext;
	}

	@Override
	public Principal getUserPrincipal() {
		return principal;
	}

	@Override
	public boolean isUserInRole(String role) {
		return principal.getRoles().contains(role);
	}

	@Override
	public boolean isSecure() {
		return originalContext.isSecure();
	}

	@Override
	public String getAuthenticationScheme() {
		return originalContext.getAuthenticationScheme();
	}

	public void setOriginalSecurityContext(SecurityContext securityContext) {
		this.originalContext = securityContext;
	}
}
