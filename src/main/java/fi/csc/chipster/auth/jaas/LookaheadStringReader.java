package fi.csc.chipster.auth.jaas;

/**
 * For reading characters/Strings out of String, with support for 1 character lookahead.
 * 
 * @author Aleksi Kallio
 *
 */
public class LookaheadStringReader {

	private String string;
	private int index = 0;
	
	public LookaheadStringReader(String string) {
		if (string == null) {
			throw new IllegalArgumentException("parameter string cannot be null");
		}
		this.string = string;		
	}

	public String lookahead() {
		return lookahead(1);
	}
	
	/**
	 * @return char wrapped in String or null if at EOS 
	 */
	public String lookahead(int lookahead) {
		int newIndex = index + lookahead - 1;
		if (newIndex < string.length()) {
			return new String(new char[] { string.charAt(newIndex) } );
		} else {
			return null;
		}
	}

	public boolean isAtEnd() {
		return index >= string.length();
	}
	
	/**
	 * String returning wrapper.
	 * 
	 * @see #readToSB(String...)
	 */
	public String readTo(String... delimeters) {
		return readToSB(delimeters).toString();
	}
	
	public StringBuffer readToSB(String... delimeters) {
		StringBuffer sb = new StringBuffer();
		while (!isAtEnd() && !anyEquals(lookahead(), delimeters)) {
			sb.append(string.charAt(index));
			index++;
		}
		return sb;		
	}

	private boolean anyEquals(String s, String[] delimeters) {
		for (String delimeter : delimeters) {
			if (delimeter.equals(s)) {
				return true;
			}
		}
		return false;
	}

	public String read() {
		if (!isAtEnd()) {
			String s = new String(new char[] { string.charAt(index) } );
			index++;
			return s;
		} else {
			return null;
		}
	}
}
