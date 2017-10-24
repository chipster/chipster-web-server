package fi.csc.chipster.rest;

public class DynamicCredentials implements CredentialsProvider {

	private String username;
	private String password;

	public void setCredentials(String username, String password) {
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