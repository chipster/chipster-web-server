package fi.csc.chipster.filebroker;

import java.io.IOException;

import javax.crypto.BadPaddingException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;

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
			if (e.getCause() instanceof BadPaddingException) {
				logger.info("cipher stream was not fully read, but it's ok if http range query was used ("
						+ e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
			} else {
				logger.info("download cancelled " + e);
			}
		}
	}
}