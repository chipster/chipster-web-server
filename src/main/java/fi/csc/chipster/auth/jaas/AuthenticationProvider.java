package fi.csc.chipster.auth.jaas;

public interface AuthenticationProvider {

	public boolean authenticate(String username, char[] password);

}
