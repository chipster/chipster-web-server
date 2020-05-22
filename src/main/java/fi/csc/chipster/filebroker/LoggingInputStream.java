package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.io.InputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LoggingInputStream extends InputStream {
	
	private static Logger logger = LogManager.getLogger();
	
	private InputStream inputStream;

	private String name;

	public LoggingInputStream(InputStream is, String name) {
		super();
		this.inputStream = is;
		this.name = name;
	}

	@Override
	public int read() throws IOException {
		logger.info(name + " read()");
		return inputStream.read();
	}
    
    public int read(byte b[], int off, int len) throws IOException {
    	logger.info(name + " read([" + b.length + "], " + off + ", " + len + ")");
		return inputStream.read(b, off, len);
	}

    
    
    public long skip(long n) throws IOException {
    	logger.info(name + " skip(" + n + ")");
		return inputStream.skip(n);
    }


    public int available() throws IOException {
    	logger.info(name + " available()");
		return inputStream.available();
    }

    public void close() throws IOException {
    	logger.info(name + " close()");
		inputStream.close();
    }
}
