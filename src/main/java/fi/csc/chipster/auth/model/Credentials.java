package fi.csc.chipster.auth.model;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class Credentials {
	
	public enum Role { CLIENT, SESSION_STORAGE }
	public enum Hash { PLAIN_TEXT }
		
	private String username;
	private String password;
	private Hash hashFunction;
	private Role role;
		
	public Credentials() { } // jaxb needs this			

	public Credentials(String username, String password, Role role, Hash hashFunction) {
		this.username = username;
		this.password = password;
		this.role = role;
		this.hashFunction = hashFunction;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public Role getRole() {
		return role;
	}

	public void setRole(Role role) {
		this.role = role;
	}

	public Hash getHashFunction() {
		return hashFunction;
	}

	public void setHashFunction(Hash hashFunction) {
		this.hashFunction = hashFunction;
	}		
}
