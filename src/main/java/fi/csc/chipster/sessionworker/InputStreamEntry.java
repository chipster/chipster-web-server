package fi.csc.chipster.sessionworker;

import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.zip.Deflater;

import fi.csc.chipster.rest.RestUtils;

/**
 * Object for defining a file that will go to a zip file
 * 
 * The code is easier to read when the collection of files is separated from technicalities of
 * the zip packaging, but we can't simply pass a list of InputStreams for two reasons: first, there is 
 * a need for a few extra information like name and compression level and second, the input stream 
 * could timeout if the InputStream is read from a remote server.  
 * 
 * @author klemela
 */
public class InputStreamEntry {
	private String name;
	private Callable<InputStream> inputStreamCallable;
	private int compressionLevel;

	public InputStreamEntry(String name, String content) {
		this(name, toCallable(content));
	}
	
	public InputStreamEntry(String name, Callable<InputStream> inputStreamCallable) {
		this(name, inputStreamCallable, Deflater.BEST_SPEED);
	}
	
	/**
	 * @param name Name of the zip file
	 * @param inputStreamCallable Callable, which returns the InputStream of the file content
	 * @param compressionLevel Compression level for this entry (e.g. Deflater.NO_COMPRESSION)
	 */
	public InputStreamEntry(String name, Callable<InputStream> inputStreamCallable, int compressionLevel) {
		this.name = name;
		this.inputStreamCallable = inputStreamCallable;
		this.compressionLevel = compressionLevel;
	}
	
	public int getCompressionLevel() {
		return compressionLevel;
	}
	
	public void setCompressionLevel(int compressionLevel) {
		this.compressionLevel = compressionLevel;
	}
	
	public Callable<InputStream> getInputStreamCallable() {
		return inputStreamCallable;
	}
	
	public void setInputStreamCallable(Callable<InputStream> inputStreamCallable) {
		this.inputStreamCallable = inputStreamCallable;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
    private static Callable<InputStream> toCallable(String str) {
    	return new Callable<InputStream>() {
			@Override
			public InputStream call() throws Exception {
				return RestUtils.toInputStream(str);
			}
		};
    }
}