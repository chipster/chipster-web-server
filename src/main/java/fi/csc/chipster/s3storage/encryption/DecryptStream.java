package fi.csc.chipster.s3storage.encryption;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import org.apache.commons.io.IOUtils;

public class DecryptStream extends InputStream {

    private BufferedCipherInputStream cipherInputStream;

    public DecryptStream(InputStream in, SecretKey secretKey, Long maxBytes) throws IOException, IllegalFileException,
            NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException {

        byte[] sigBytes = new byte[FileEncryption.CHIPSTER_ENC_SIG.getBytes().length];
        byte[] ivBytes = new byte[FileEncryption.V1_IV_SIZE];

        // check file format signature
        if (IOUtils.read(in, sigBytes) != sigBytes.length) {
            throw new IllegalFileException("wrong file format signature");
        }

        // read iv
        if (IOUtils.read(in, ivBytes) != ivBytes.length) {
            throw new IllegalFileException("no IV data");
        }

        Cipher cipher = Cipher.getInstance(FileEncryption.V1_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(ivBytes));

        this.cipherInputStream = new BufferedCipherInputStream(in, cipher, maxBytes);
    }

    @Override
    public int read() throws IOException {
        return cipherInputStream.read();
    }

    @Override
    public int read(byte[] b) throws IOException {

        return cipherInputStream.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return cipherInputStream.read(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        return cipherInputStream.skip(n);
    }

    @Override
    public int available() throws IOException {
        return cipherInputStream.available();
    }

    @Override
    public void close() throws IOException {
        cipherInputStream.close();
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
