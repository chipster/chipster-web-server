package fi.csc.chipster.auth.model;

import java.io.Serializable;

import javax.persistence.Embeddable;

@Embeddable
public class UserId implements Serializable {
	
	private static final String DELIMITER = "/";
	
	private String username;
	private String auth;
	
	public UserId() { }
	
	public UserId(String auth, String username) {
		this.auth = auth;
		this.username = username;
	}
	
	public UserId(String userId) {
		String[] parts = userId.split(DELIMITER);
		if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
			this.auth = parts[0];
			this.username = parts[1];
		}
	}
	
	public String getUsername() {
		return username;
	}
	public void setUsername(String username) {
		this.username = username;
	}
	public String getAuth() {
		return auth;
	}
	public void setAuth(String auth) {
		this.auth = auth;
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((auth == null) ? 0 : auth.hashCode());
		result = prime * result + ((username == null) ? 0 : username.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		UserId other = (UserId) obj;
		if (auth == null) {
			if (other.auth != null)
				return false;
		} else if (!auth.equals(other.auth))
			return false;
		if (username == null) {
			if (other.username != null)
				return false;
		} else if (!username.equals(other.username))
			return false;
		return true;
	}
	
	public String toUserIdString() {
		if (auth.contains(DELIMITER) || username.contains(DELIMITER)) {
			throw new IllegalStateException(DELIMITER + " not allowed in username (" + username + ")");
		}
		return this.auth + DELIMITER + this.username;
	}
}
