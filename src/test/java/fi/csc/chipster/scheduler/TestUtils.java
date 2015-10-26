package fi.csc.chipster.scheduler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TestUtils {
	
	public static final Logger logger = LogManager.getLogger();
	
	public static boolean threadSafeAssert(Object o1, Object o2) {
		if (!o1.equals(o2)) {
			logger.error("assert failed, was: " + o2 + "(" + o1.getClass().getSimpleName() + "), expected: " + o1 + "(" + o2.getClass().getSimpleName() + ")");
			return false;
		}
		return true;
	}
}
