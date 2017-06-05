package fi.csc.chipster.sessionworker;

import java.io.IOException;
import java.io.InputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class NonClosableInputStream extends InputStream {

	private static Logger logger = LogManager.getLogger();

	private InputStream in;

	public NonClosableInputStream(InputStream in) {
		this.in = in;
	}

	@Override
	public void close() throws IOException {
		logger.debug("zip input stream close ignored");
		//super.close();
	}

	public void closeForReal() throws IOException {
		in.close();
	}

	@Override
	public int read() throws IOException {
		return in.read();
	}

	// interface requires only read() function, but reading with it would be really slow
	@Override
	public int read(byte[] b) throws IOException {
		return in.read(b);
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		return in.read(b, off, len);
	}
}