package fi.csc.chipster.auth.model;

import java.time.LocalDateTime;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.xml.bind.annotation.XmlRootElement;

@Entity // db
@XmlRootElement // json
public class Token {
		
	@Id // db
	private String tokenKey;
	private String username;
	private Role role;
	private LocalDateTime valid;
	
	public Token() {
		// JAX-B needs this
	}
	
	public Token(String username, Role role, String token,
			LocalDateTime valid) {
		this.username = username;
		this.role = role;
		this.tokenKey = token;
		this.valid = valid;
	}
	public String getUsername() {
		return username;
	}
	public void setUsername(String username) {
		this.username = username;
	}
	public Role getRole() {
		return role;
	}
	public void setRole(Role role) {
		this.role = role;
	}
	public String getTokenKey() {
		return tokenKey;
	}
	public void setTokenKey(String token) {
		this.tokenKey = token;
	}
	public LocalDateTime getValid() {
		return valid;
	}
	public void setValid(LocalDateTime valid) {
		this.valid = valid;
	}
}
