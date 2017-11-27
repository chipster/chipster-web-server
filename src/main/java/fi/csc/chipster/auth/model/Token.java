package fi.csc.chipster.auth.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonIgnore;

import fi.csc.chipster.rest.RestUtils;

@Entity // db
@XmlRootElement // json
public class Token {
		
	@Id // db
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID tokenKey;
	private String username;
	private Instant	validUntil, created;
	private String rolesJson;
	
	public Token() {
		// JAX-B needs this
	}
	
	public Token(String username, UUID token,
			Instant validUntil, Instant created, String rolesJson) {
		this.username = username;
		this.tokenKey = token;
		this.validUntil = validUntil;
		this.created = created;
		this.setRolesJson(rolesJson);
	}
	
	public String getUsername() {
		return username;
	}
	
	public void setUsername(String username) {
		this.username = username;
	}
	
	public UUID getTokenKey() {
		return tokenKey;
	}
	
	public void setTokenKey(UUID token) {
		this.tokenKey = token;
	}

	public String getRolesJson() {
		return rolesJson;
	}

	public void setRolesJson(String rolesJson) {
		this.rolesJson = rolesJson;
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
	
	@JsonIgnore
	@SuppressWarnings("unchecked")
	public HashSet<String> getRoles() {
		return RestUtils.parseJson(HashSet.class, rolesJson);
	}
}
