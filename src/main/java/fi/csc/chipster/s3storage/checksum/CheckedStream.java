package fi.csc.chipster.s3storage.checksum;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CheckedInputStream;
import java.util.zip.Checksum;

import org.apache.commons.io.input.BoundedInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.s3storage.FileLengthException;

public class CheckedStream extends InputStream {

    private final static Logger logger = LogManager.getLogger();

    private CheckedInputStream checkedInputStream;
    private Checksum checksum;

    private String expectedChecksum;
    private String streamChecksum;

    private BoundedInputStream boundedInputStream;

    private Long expectedLength;

    private Long streamLength;

    private InputStream sourceStream;

    /**
     * Count and verify stream length and checksum
     * 
     * If the stream length or checksum are known, this stream can be used to verify
     * that the stream matches them. Otherwise, this stream can be used to calculate
     * them.
     * 
     * Stream length is always calculated. Checksum is calculated if algorithm is
     * given.
     * 
     * Verifications can be skipped by giving null as the expectedLength and/or
     * expectedChecksum.
     * 
     * @param in               Stream to verify
     * @param expectedChecksum Throw exception if this and algorithm is provided and
     *                         the stream doesn't match it
     * @param algorithm        Checksum algorithm
     * @param expectedLength   Throw exception if this is provided and the stream
     *                         length doesn't match it
     * @throws IOException
     */
    public CheckedStream(InputStream in, String expectedChecksum, Checksum algorithm, Long expectedLength)
            throws IOException {

        this.expectedLength = expectedLength;
        this.expectedChecksum = expectedChecksum;
        this.checksum = algorithm;

        this.boundedInputStream = BoundedInputStream.builder().setInputStream(in).get();

        if (algorithm != null) {
            this.checkedInputStream = new CheckedInputStream(this.boundedInputStream, algorithm);
            this.sourceStream = this.checkedInputStream;
        } else {
            this.sourceStream = this.boundedInputStream;
        }
    }

    private void end() throws ChecksumException {

        this.streamLength = this.boundedInputStream.getCount();

        if (this.checksum != null) {

            this.streamChecksum = Long.toHexString(this.checksum.getValue());
        }

        if (this.expectedLength != null) {
            if (this.expectedLength.equals(streamLength)) {
                logger.debug("length ok: " + expectedLength);
            } else {

                throw new FileLengthException("expected " + this.expectedLength + " bytes, but was: "
                        + streamLength + " bytes");
            }
        } else {
            logger.debug("length not available");
        }

        if (this.expectedChecksum != null) {
            if (this.expectedChecksum.equals(streamChecksum)) {
                logger.debug("checksum ok: " + expectedChecksum);
            } else {

                throw new ChecksumException("checksum error");
            }
        } else {
            logger.debug("checksum not available");
        }
    }

    @Override
    public int read() throws IOException {

        int b = sourceStream.read();

        if (b == -1) {
            end();
        }
        return b;
    }

    @Override
    public int read(byte[] b) throws IOException {

        int bytes = sourceStream.read(b);

        if (bytes == -1) {

            end();
        }

        return bytes;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int bytes = sourceStream.read(b, off, len);

        if (bytes == -1) {
            end();
        }

        return bytes;
    }

    @Override
    public long skip(long n) throws IOException {
        return sourceStream.skip(n);
    }

    @Override
    public int available() throws IOException {
        return sourceStream.available();
    }

    @Override
    public void close() throws IOException {
        sourceStream.close();
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    public String getStreamChecksum() {
        if (this.streamChecksum != null) {
            return this.streamChecksum;
        }
        throw new IllegalStateException("stream hasn't ended yet");
    }

    public long getLength() {
        if (this.streamLength != null) {
            return this.streamLength;
        }
        throw new IllegalStateException("stream hasn't ended yet");
    }
}
