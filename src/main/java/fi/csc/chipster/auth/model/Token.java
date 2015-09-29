package fi.csc.chipster.auth.model;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.xml.bind.annotation.XmlRootElement;

import fi.csc.chipster.rest.RestUtils;

@Entity // db
@XmlRootElement // json
public class Token {
		
	@Id // db
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID tokenKey;
	private String username;
	private LocalDateTime valid;
	private String rolesJson;
	
	public Token() {
		// JAX-B needs this
	}
	
	public Token(String username, UUID token,
			LocalDateTime valid, String rolesJson) {
		this.username = username;
		this.tokenKey = token;
		this.valid = valid;
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
	public LocalDateTime getValid() {
		return valid;
	}
	public void setValid(LocalDateTime valid) {
		this.valid = valid;
	}

	public String getRolesJson() {
		return rolesJson;
	}

	public void setRolesJson(String rolesJson) {
		this.rolesJson = rolesJson;
	}

	@SuppressWarnings("unchecked")
	public HashSet<String> getRoles() {
		return RestUtils.parseJson(HashSet.class, rolesJson);
	}
}
