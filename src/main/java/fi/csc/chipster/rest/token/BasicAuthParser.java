package fi.csc.chipster.rest.token;

import jakarta.xml.bind.DatatypeConverter;

import fi.csc.chipster.rest.exception.NotAuthorizedException;

public class BasicAuthParser {

	private String username;
	private String password;

	public BasicAuthParser(String auth) {
		
        // remove auth type
        auth = auth.replaceFirst("[B|b]asic ", "");

        // decode to byte array
        byte[] decodedBytes = DatatypeConverter.parseBase64Binary(auth);

        if(decodedBytes == null || decodedBytes.length == 0){
            throw new NotAuthorizedException("authorization header is null or empty");
        }

		String[] credentials =  new String(decodedBytes).split(":", 2);

		//If login or password fail
		if(credentials == null || credentials.length != 2){
			throw new NotAuthorizedException("username or password missing");
		}		

		setUsername(credentials[0]);
		setPassword(credentials[1]);
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
}