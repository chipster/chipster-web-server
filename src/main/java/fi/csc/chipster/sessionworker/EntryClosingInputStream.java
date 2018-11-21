package fi.csc.chipster.sessionworker;

import java.io.FilterInputStream;
import java.io.IOException;
import java.util.zip.ZipInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class EntryClosingInputStream extends FilterInputStream {

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private ZipInputStream in;

	public EntryClosingInputStream(ZipInputStream in) {
		super(in);
		this.in = in;
	}

	@Override
	public void close() throws IOException {
		in.closeEntry();
	}
}