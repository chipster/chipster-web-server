package fi.csc.chipster.filebroker;

import java.io.IOException;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.ext.WriterInterceptor;
import javax.ws.rs.ext.WriterInterceptorContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Don't log stack trace when download is cancelled
 * 
 * @author klemela
 *
 */
public class ExceptionWriterInterceptor implements WriterInterceptor {
	
	private static Logger logger = LogManager.getLogger();

	@Override
	public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
		try {
	        context.setOutputStream(context.getOutputStream());
	        context.proceed();
		} catch (IOException e) {
			logger.info("download cancelled " + e);
		}
	}
}