package fi.csc.chipster.comp;

public class Exceptions {

	public static String getStackTrace(Throwable throwable) {

		String trace = throwable.getClass().getSimpleName();
		if (throwable.getMessage() != null) {
			trace += ": " + throwable.getMessage();
		}
		trace += "\n";

		trace += getStackTrace(throwable.getStackTrace());

		if (throwable.getCause() != null) {
			trace += "Caused by: ";
			trace += getStackTrace(throwable.getCause());
		}
		return trace;
	}

	public static String getStackTrace(StackTraceElement[] stackTrace) {
		String trace = "";
		for (StackTraceElement element : stackTrace) {
			trace += (element.toString() + "\n");
		}
		return trace;
	}
}
