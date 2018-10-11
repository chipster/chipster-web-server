package fi.csc.chipster.rest.hibernate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.EmptyInterceptor;

public class DelayInterceptor extends EmptyInterceptor {
		
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private static final long serialVersionUID = 1L;

	@Override
	public String onPrepareStatement(String sql) {
//			logger.info("onPrepareStatement() " + sql);
		try {
			Thread.sleep(1);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return super.onPrepareStatement(sql);
	}
}