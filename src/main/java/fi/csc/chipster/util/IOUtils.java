package fi.csc.chipster.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;

/**
 * 
 * @author Aleksi Kallio
 * 
 */
public class IOUtils {

	private static final int BUFFER_SIZE = 16*1024; 
	
	/**
	 * Closes Reader if it is not null. Ignores all exceptions. Useful for those finally-blocks.
	 */
	public static void closeIfPossible(Reader reader) {
		if (reader != null) {
			try {
				reader.close();
			} catch (IOException e) {
				// ignore
			}
		}
	}

	/**
	 * Closes Writer if it is not null. Ignores all exceptions. Useful for those finally-blocks.
	 */
	public static void closeIfPossible(Writer writer) {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException e) {
				// ignore
			}
		}
	}

	/**
	 * Closes InputStream if it is not null. Ignores all exceptions. Useful for those finally-blocks.
	 */
	public static void closeIfPossible(InputStream stream) {
		if (stream != null) {
			try {
				stream.close();
			} catch (IOException e) {
				// ignore
			}
		}
	}

	/**
	 * Closes OutputStream if it is not null. Ignores all exceptions. Useful for those finally-blocks.
	 */
	public static void closeIfPossible(OutputStream stream) {
		if (stream != null) {
			try {
				stream.close();
			} catch (IOException e) {
				// ignore
			}
		}
	}

	public static void disconnectIfPossible(HttpURLConnection connection) {
		if (connection != null) {
			connection.disconnect();
		}
	}
	
	/**
	 * 
	 * Copies stream contents of source to target and reports progress.
	 * 
	 * @param source input stream
	 * @param target output stream
	 * @param progressListener can be null
	 * 
	 * @throws IOException all exceptions from underlying IO are passed through
	 */
	public static void copy(InputStream source, OutputStream target) throws IOException {
		
		BufferedInputStream bSource = new BufferedInputStream(source);
		BufferedOutputStream bTarget = new BufferedOutputStream(target);
		
		// initialise
		byte buffer[] = new byte[BUFFER_SIZE];
		int len = BUFFER_SIZE;	

		// copy while there is content
		while (true) {
			len = bSource.read(buffer, 0, BUFFER_SIZE);
			if (len < 0) {
				break;
			}
			
			bTarget.write(buffer, 0, len);	
		}
		bTarget.flush();
	}	
	
	public static void copy(InputStream source, File target) throws IOException {
		FileOutputStream out = new FileOutputStream(target);
		try {
			copy(source, out);
		} finally {
			closeIfPossible(out);
		}
	}
}
