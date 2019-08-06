package fi.csc.chipster.auth.model;

import java.time.Instant;
import java.util.Set;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement // json
public class ParsedToken {
		
	private String tokenKey;
	private String username;
	private Instant	validUntil, created;
	private Set<String> roles;
	private String name;

	public ParsedToken() {
		// JAX-B needs this
	}
	
	public ParsedToken(String username, String token,
			Instant validUntil, Instant created, Set<String> roles) {
		this.username = username;
		this.tokenKey = token;
		this.validUntil = validUntil;
		this.created = created;
		this.roles = roles;
	}
	
	public String getUsername() {
		return username;
	}
	
	public void setUsername(String username) {
		this.username = username;
	}
	
	public String getTokenKey() {
		return tokenKey;
	}
	
	public void setTokenKey(String token) {
		this.tokenKey = token;
	}

	public Instant getValidUntil() {
		return validUntil;
	}

	public void setValidUntil(Instant validUntil) {
		this.validUntil = validUntil;
	}
	
	public Instant getCreated() {
		return created;
	}

	public void setCreated(Instant created) {
		this.created = created;
	}
		
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Set<String> getRoles() {
		return roles;
	}

	public void setRoles(Set<String> roles) {
		this.roles = roles;
	}
}
