package fi.csc.chipster.s3storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ChecksumStream extends InputStream {

    private final static Logger logger = LogManager.getLogger();

    private CheckedInputStream checkedInputStream;
    private CRC32 crc32;
    private String checksum;

    protected ChecksumStream(InputStream in, String checksum) {

        this.crc32 = new CRC32();

        this.checkedInputStream = new CheckedInputStream(in, crc32);

        this.checksum = checksum;
    }

    private void end() {

        String streamChecksum = Long.toHexString(this.crc32.getValue());

        if (this.checksum != null) {
            if (this.checksum.equals(streamChecksum)) {
                logger.info("checksum ok: " + checksum);
            } else {
                throw new ChecksumException("checksum error");
            }
        } else {
            logger.info("checksum not available");
        }
    }

    @Override
    public int read() throws IOException {

        int b = checkedInputStream.read();

        if (b == -1) {
            end();
        }
        return b;
    }

    @Override
    public int read(byte[] b) throws IOException {

        int bytes = checkedInputStream.read(b);

        if (bytes == -1) {
            end();
        }

        return bytes;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int bytes = checkedInputStream.read(b, off, len);

        if (bytes == -1) {
            end();
        }

        return bytes;
    }

    @Override
    public long skip(long n) throws IOException {
        return checkedInputStream.skip(n);
    }

    @Override
    public int available() throws IOException {
        return checkedInputStream.available();
    }

    @Override
    public void close() throws IOException {
        checkedInputStream.close();
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}