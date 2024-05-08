package fi.csc.chipster.s3storage.encryption;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;

import org.apache.commons.io.input.BoundedInputStream;

/**
 * Proper buffering for CipherInputStream
 * 
 * CipherInputStream has a too small fixed buffer of only 512 bytes. It causes
 * problems on the both sides of the stream. Firstly, on the input side, it
 * always tries to read only 512 bytes, which creates too many syscalls.
 * This is easy to fix with BufferedInputStream. Secondly, on the output side,
 * the CipherInputStream always returns only 512 bytes, regardless of the buffer
 * size. This is fixed with DiligentInputStream, which keeps reading until the
 * buffer is filled.
 * 
 * This improves performance a lot, for example encrypting a file on M1 and SSD
 * went from 140 MiB/s to 700 MiB/s in EncryptDecryptBenchmark.java.
 */
public class BufferedCipherInputStream extends FilterInputStream {

    /**
     * 
     * @param in
     * @param cipher
     * @param maxBytes Stop reading after this many bytes. Set to null, if you
     *                 intend to read the whole stream. This is neededed for range
     *                 queries, where we want to read from that start of the file,
     *                 but don't usually reach the end of it. Otherwise
     *                 CipherInputStream will throw BadBaddingExcpetion at the end
     *                 of the partial stream.
     * 
     *                 This is counted on output size of the CipherStream, i.e.
     *                 plaintext bytes when doing encryption.
     * @throws IOException
     */
    public BufferedCipherInputStream(InputStream in, Cipher cipher, Long maxBytes) throws IOException {
        super(getStream(in, cipher, maxBytes));
    }

    private static InputStream getStream(InputStream in, Cipher cipher, Long maxBytes) throws IOException {
        InputStream cipherStream = new CipherInputStream(new BufferedInputStream(in, 1 << 16), cipher);

        if (maxBytes != null) {
            cipherStream = BoundedInputStream.builder().setInputStream(cipherStream).setMaxCount(maxBytes).get();
        }
        return new DiligentInputStream(cipherStream);
    }
}
