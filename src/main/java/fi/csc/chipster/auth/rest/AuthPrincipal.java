package fi.csc.chipster.auth.rest;

import java.security.Principal;

public class AuthPrincipal implements Principal {
	
	String username;

	public AuthPrincipal(String username) {
		this.username = username;
	}

	@Override
	public String getName() {
		return username;
	}

}
