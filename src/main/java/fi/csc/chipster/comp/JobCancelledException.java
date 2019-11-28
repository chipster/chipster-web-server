package fi.csc.chipster.comp;

public class JobCancelledException extends CompException {

	public JobCancelledException(Exception cause) {
		super(cause);
	}

	public JobCancelledException(String message) {
		super(message);
	}

	public JobCancelledException() {
		super();
	}
}
