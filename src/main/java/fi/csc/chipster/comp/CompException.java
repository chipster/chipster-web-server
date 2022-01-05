/*
 * Created on Feb 24, 2005
 *
 */
package fi.csc.chipster.comp;

/**
 * Problem when running analysis jobs.
 * 
 * @author hupponen
 */
public class CompException extends Exception {

	public CompException(String message) {
		super(message);
	}

	public CompException(Exception cause) {
		super(cause);
	}

	public CompException() {
		super();
	}

	public CompException(String message, Exception cause) {
		super(message, cause);
	}
}
