package fi.csc.chipster.auth.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * Super class for all Chipster tokens
 * 
 * @author klemela
 *
 */
@XmlRootElement // json
public class ChipsterToken {
	
	private String username;
	private Instant	validUntil;
	private Set<String> roles;

	public ChipsterToken() {
		// JAX-B needs this
	}
	
	public ChipsterToken(String username,
			Instant validUntil, Set<String> roles) {
		this.username = username;
		this.validUntil = validUntil;
		this.roles = roles;
	}
	
	public ChipsterToken(String username, Instant valid, String role) {
		this(username, valid, new HashSet<String>() {{ add(role); }});
	}

	public String getUsername() {
		return username;
	}
	
	public void setUsername(String username) {
		this.username = username;
	}

	public Instant getValidUntil() {
		return validUntil;
	}

	public void setValidUntil(Instant validUntil) {
		this.validUntil = validUntil;
	}

	public Set<String> getRoles() {
		return roles;
	}

	public void setRoles(Set<String> roles) {
		this.roles = roles;
	}
}
