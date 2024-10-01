package fi.csc.chipster.s3storage.encryption;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Stream which keeps reading until the buffer is full or the stream ends
 */
public class DiligentInputStream extends FilterInputStream {

    public DiligentInputStream(InputStream in) {
        super(in);
    }

    @Override
    public int read(byte[] b) throws IOException {

        int bytes = in.readNBytes(b, 0, b.length);

        if (bytes == 0) {
            // end of stream
            return -1;
        }

        return bytes;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {

        int bytes = in.readNBytes(b, off, len);

        if (bytes == 0) {
            // end of stream
            return -1;
        }

        return bytes;
    }
}