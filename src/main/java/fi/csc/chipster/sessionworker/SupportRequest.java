package fi.csc.chipster.sessionworker;

public class SupportRequest {
	private String message;
	private String errorMessage;
	private String mail;
	private String session;
	private String app;
	
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
	
	public String getErrorMessage() {
		return errorMessage;
	}
	
	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public String getApp() {
		return app;
	}

	public void setApp(String app) {
		this.app = app;
	}
}
