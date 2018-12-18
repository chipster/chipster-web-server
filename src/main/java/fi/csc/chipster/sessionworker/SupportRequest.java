package fi.csc.chipster.sessionworker;

public class SupportRequest {
	private String message;
	private String errorMessage;
	private String mail;
	private String session;
	private boolean isReadWrite;
	
	public String getMessage() {
		return message;
	}
	
	public void setMessage(String message) {
		this.message = message;
	}
	
	public String getMail() {
		return mail;
	}
	
	public void setMail(String email) {
		this.mail = email;
	}
	
	public String getSession() {
		return session;
	}
	
	public void setSession(String session) {
		this.session = session;
	}
	
	public boolean isReadWrite() {
		return isReadWrite;
	}
	
	public void setReadWrite(boolean isReadWrite) {
		this.isReadWrite = isReadWrite;
	}
	
	public String getErrorMessage() {
		return errorMessage;
	}
	
	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}
}
