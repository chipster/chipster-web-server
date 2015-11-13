package fi.csc.chipster.rest;

/**
 * Interface for credentials. Implementation can take care that the credentials
 * won't expire and refresh them when necessary. 
 * 
 * @author klemela
 */
public interface CredentialsProvider {
	public String getUsername();
	public String getPassword();
}
