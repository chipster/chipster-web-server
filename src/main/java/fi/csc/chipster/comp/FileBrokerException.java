package fi.csc.chipster.comp;

public class FileBrokerException extends Exception {

	public FileBrokerException() {
		super();
	}

	public FileBrokerException(String message) {
		super(message);
	}

	public FileBrokerException(Exception e) {
		super(e);
	}

	public FileBrokerException(String message, Exception cause) {
		super(message, cause);
	}
}
