package fi.csc.chipster.rest;

public class StaticCredentials implements CredentialsProvider {

	private String username;
	private String password;

	public StaticCredentials(String username, String password) {
		this.username = username;
		this.password = password;
	}

	@Override
	public String getUsername() {
		return username;
	}

	@Override
	public String getPassword() {
		return password;
	}	
}
