package fi.csc.chipster.s3storage.encryption;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;

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

    public BufferedCipherInputStream(InputStream in, Cipher cipher) {
        super(new DiligentInputStream(new CipherInputStream(new BufferedInputStream(in, 1 << 16), cipher)));
    }
}
