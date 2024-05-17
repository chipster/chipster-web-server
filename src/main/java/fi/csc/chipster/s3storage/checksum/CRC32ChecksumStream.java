package fi.csc.chipster.s3storage.checksum;

import java.io.InputStream;
import java.util.zip.CRC32;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CRC32ChecksumStream extends ChecksumStream {

    @SuppressWarnings("unused")
    private final static Logger logger = LogManager.getLogger();

    public CRC32ChecksumStream(InputStream in, String expectedChecksum) {

        super(in, expectedChecksum, new CRC32());
    }
}
