package fi.csc.chipster.s3storage.encryption;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import org.apache.commons.lang3.ArrayUtils;

/**
 * Encrypt a stream using Chipster encryption format
 * 
 * A random IV is generated for each file. The file format signature and IV are
 * written to the beginning of the output.
 * 
 * @see FileEncryption
 */
public class EncryptStream extends InputStream {

    private SequenceInputStream sequenceInputStream;

    public EncryptStream(InputStream in, SecretKey secretKey, SecureRandom secureRandom)
            throws IOException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException {

        byte[] iv = new byte[FileEncryption.V1_IV_SIZE];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(FileEncryption.V1_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));

        byte[] headerBytes = ArrayUtils.addAll(FileEncryption.CHIPSTER_ENC_SIG.getBytes(), iv);

        ByteArrayInputStream headerStream = new ByteArrayInputStream(headerBytes);

        InputStream cipherInputStream = new BufferedCipherInputStream(in, cipher, null);

        this.sequenceInputStream = new SequenceInputStream(headerStream, cipherInputStream);
    }

    @Override
    public int read() throws IOException {
        return sequenceInputStream.read();
    }

    @Override
    public int read(byte[] b) throws IOException {

        return sequenceInputStream.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return sequenceInputStream.read(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        return sequenceInputStream.skip(n);
    }

    @Override
    public int available() throws IOException {
        return sequenceInputStream.available();
    }

    @Override
    public void close() throws IOException {
        sequenceInputStream.close();
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
