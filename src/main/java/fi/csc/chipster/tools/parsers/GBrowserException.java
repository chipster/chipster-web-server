package fi.csc.chipster.tools.parsers;

public class GBrowserException extends Exception {

	public GBrowserException(String string) {
		super(string);
	}

	public GBrowserException(String string, Exception e) {
		super(string, e);
	}
}
