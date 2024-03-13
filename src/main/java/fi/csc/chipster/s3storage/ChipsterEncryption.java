package fi.csc.chipster.s3storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import org.apache.commons.io.IOUtils;

public class ChipsterEncryption {

    // other programs can't read our files anyway, so let's make it easier for us to
    // recognize them

    // v1: this signature, 16 bytes of iv and then ciphertext in
    // AES/CBC/PKCS5Padding
    public static final String CHIPSTER_ENC_SIG = "chipster-encrypted-file-v1.";

    /*
     * Why to use AES directly and not PGP?
     * ------------------------------------
     * 
     * PGP would be nice, because it provides a standardized file format. However,
     * its speed in Java on x64 is a bit slow.
     * 
     * how java pgp was tested: armor: false, integrity: true, CEST5 -> AES256,
     * without BC provider
     * https://github.com/bcgit/bc-java/blob/main/pg/src/main/java/org/bouncycastle/
     * openpgp/examples/KeyBasedLargeFileProcessor.java
     * 
     * Apple M1:
     * 
     * java encrypt pgp 294 MB/s
     * java decrypt pgp 146 MB/s
     * java encrypt aes 588 MB/s
     * java decrypt aes 1154 MB/s
     * native gpg encrypt: 204 MB/s
     * 
     * Intel Skylake:
     * 
     * java encrypt pgp 73 MB/s
     * java decrypt pgp 33 MB/s
     * java encrypt aes 148 MB/s
     * java decrypt aes 230 MB/s
     * native gpg encrypt: 165 MB/s
     * 
     * AMD EPYC:
     * 
     * java encrypt pgp 188 MB/s
     * java decrypt pgp 82 MB/s
     * java encrypt aes 408 MB/s
     * java decrypt aes 662 MB/s
     * 
     * Why CBC?
     * --------
     * 
     * - no practical max data size like in GCM
     * https://crypto.stackexchange.com/questions/51518/maximum-number-of-blocks-to-
     * be-encrypted-under-one-key-in-cbc-and-ctr-mode
     * - it's enough to keep the data secret if s3 access control leaks, we don't
     * really care about message authentication
     * - creating a separate random key and iv for each file should reduce remaining
     * risks
     */
    public static final String V1_ALGORITHM = "AES/CBC/PKCS5Padding";
    public static final int V1_IV_SIZE = 16;

    final static SecureRandom secureRandom = new SecureRandom();

    public static void encrypt(SecretKey secretKey, final File input,
            final File outputFile)
            throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IOException, IllegalBlockSizeException, BadPaddingException {

        byte[] iv = new byte[16];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(V1_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));

        try (FileInputStream inputStream = new FileInputStream(input);
                FileOutputStream outputStream = new FileOutputStream(outputFile)) {

            // write chipster signature
            outputStream.write(CHIPSTER_ENC_SIG.getBytes());

            // write iv
            outputStream.write(iv);

            // write ciphertext
            byte[] buffer = new byte[1 << 16];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byte[] output = cipher.update(buffer, 0, bytesRead);
                if (output != null) {
                    outputStream.write(output);
                }
            }
            byte[] outputBytes = cipher.doFinal();
            if (outputBytes != null) {
                outputStream.write(outputBytes);
            }
        }
    }

    public static void decrypt(SecretKey secretKey, final File input,
            final File outputFile)
            throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IOException, IllegalBlockSizeException, BadPaddingException,
            IllegalFileException {

        try (FileInputStream inputStream = new FileInputStream(input);
                FileOutputStream outputStream = new FileOutputStream(outputFile)) {

            byte[] sigBytes = new byte[CHIPSTER_ENC_SIG.getBytes().length];
            byte[] ivBytes = new byte[V1_IV_SIZE];

            // check file format signature
            if (IOUtils.read(inputStream, sigBytes) != sigBytes.length) {
                throw new IllegalFileException("wrong file format signature");
            }

            // read iv
            if (IOUtils.read(inputStream, ivBytes) != ivBytes.length) {
                throw new IllegalFileException("no IV data");
            }

            Cipher cipher = Cipher.getInstance(V1_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(ivBytes));

            // decrypt ciphertext
            byte[] buffer = new byte[1 << 16];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byte[] output = cipher.update(buffer, 0, bytesRead);
                if (output != null) {
                    outputStream.write(output);
                }
            }
            byte[] output = cipher.doFinal();
            if (output != null) {
                outputStream.write(output);
            }
        }
    }
}