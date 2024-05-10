package fi.csc.chipster.s3storage.checksum;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Count and verify stream length and CRC32 checksum
 * 
 * @see CheckedStream
 */
public class CRC32CheckedStream extends CheckedStream {

    @SuppressWarnings("unused")
    private final static Logger logger = LogManager.getLogger();

    public CRC32CheckedStream(InputStream in, String expectedChecksum, Long expectedLength) throws IOException {

        super(in, expectedChecksum, new CRC32(), expectedLength);
    }
}
