package fi.csc.chipster.sessionworker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;

import fi.csc.chipster.rest.Config;

/**
 * Size limits for the session extraction
 *
 * Limit the bytes decompressed from the session zip to memory, so that a small
 * zip file can't consume all memory of the session-worker. Limit also the
 * number of zip entries, because each data file creates a dataset in the
 * session-db and an upload to the file-broker. Create a new instance for each
 * extraction, because the limits are counted per session.
 */
public class SessionLimits {

	public static final String CONF_MAX_XML_CHECK_SIZE = "session-worker-max-xml-check-size";
	public static final String CONF_MAX_XML_SIZE = "session-worker-max-xml-size";
	public static final String CONF_MAX_METADATA_SIZE = "session-worker-max-metadata-size";
	public static final String CONF_MAX_ENTRIES = "session-worker-max-entries";

	private long maxXmlCheckSize;
	private long maxXmlSize;
	private long metadataRemaining;
	private long maxEntries;
	private long entryCount = 0;

	static final long MiB = 1024 * 1024;

	/**
	 * Read the limits from the config, where they are in MiB
	 */
	public SessionLimits(Config config) {
		this(config.getLong(CONF_MAX_XML_CHECK_SIZE) * MiB, config.getLong(CONF_MAX_XML_SIZE) * MiB,
				config.getLong(CONF_MAX_METADATA_SIZE) * MiB, config.getLong(CONF_MAX_ENTRIES));
	}

	/**
	 * Size limits in bytes
	 */
	public SessionLimits(long maxXmlCheckSize, long maxXmlSize, long maxMetadataSize, long maxEntries) {
		this.maxXmlCheckSize = maxXmlCheckSize;
		this.maxXmlSize = maxXmlSize;
		this.metadataRemaining = maxMetadataSize;
		this.maxEntries = maxEntries;
	}

	/**
	 * Count one data file entry of the zip
	 */
	public void countEntry() throws ZipException {
		entryCount++;
		checkEntryCount(entryCount);
	}

	/**
	 * Check the number of data files when it's known beforehand, e.g. from the zip
	 * directory
	 */
	public void checkEntryCount(long count) throws ZipException {
		if (count > maxEntries) {
			throw new ZipException("session has more than " + maxEntries + " files");
		}
	}

	/**
	 * How many bytes to download from the beginning of the zip to check the
	 * version of the XmlSession
	 */
	public long getMaxXmlCheckSize() {
		return maxXmlCheckSize;
	}

	/**
	 * Limit the size of the session.xml of the XmlSession
	 */
	public InputStream limitXml(InputStream in) throws IOException {
		return limit(in, maxXmlSize, "session.xml is larger than " + maxXmlSize + " bytes");
	}

	/**
	 * Read a metadata entry to a String
	 *
	 * All entries read with this method are counted against the same total limit.
	 */
	public String readMetadata(InputStream in, String entryName) throws IOException {
		BoundedInputStream bounded = limit(in, metadataRemaining,
				"session metadata is larger than the limit, when reading " + entryName);
		String str = IOUtils.toString(bounded, StandardCharsets.UTF_8);
		metadataRemaining -= bounded.getCount();
		return str;
	}

	private static BoundedInputStream limit(InputStream in, long max, String message) throws IOException {
		// read one byte over the limit to notice if there is more
		return BoundedInputStream.builder()
				.setInputStream(in)
				.setMaxCount(max + 1)
				.setPropagateClose(false)
				.setOnMaxCount((maxCount, count) -> {
					// ZipException shows the message to the user
					throw new ZipException(message);
				})
				.get();
	}
}
