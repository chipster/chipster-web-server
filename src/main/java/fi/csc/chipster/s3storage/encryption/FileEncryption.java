package fi.csc.chipster.s3storage.encryption;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;

/**
 * <h1>Encrypt and decrypt file streams</h1>
 * 
 * <h2>Threat model</h2>
 * <p>
 * Encryption at rest is a great idea, especially when saving data in object
 * storage, where a simple mistake in bucket configuration can make all data
 * publicly readable.
 * </p>
 * 
 * <h2>Encryption scheme</h2>
 * 
 * <p>
 * A standard way for this would be the PGP file format, and
 * there is even a nice Java implementation for it. Unfortunately the
 * performance of this Java implementation was unsatisfactory, < 100 MiB/s
 * decryption on x64 architectures (see Appendix 1). The native gpg client
 * performs better (around 200 MiB/s), but it would be cumbersome to pass
 * Java streams through it, especially considering its stateful key management
 * interface.
 * </p>
 * 
 * <p>
 * More modern inspiration could be taken from TLS and it's use of AES-256-GCM,
 * but it shouldn't be used for records larger than 64 GB
 * (https://crypto.stackexchange.com/questions/31793/plain-text-size-limits-for-aes-gcm-mode-just-64gb).
 * TLS apparently gets around this by updating keys after certain amount of data
 * has been encrypted. However, there doesn't seem to exists a standard file
 * format for storing a stream of GCM records on disk. Additionally, it would be
 * daunting to rotate the encryption IV ourselves.
 * </p>
 * 
 * <p>
 * The CBC mode seems to offer a good balance here. It doesn't have known
 * catastrophic vulnerabilities, when we create a separate random key
 * and IV for each file. It doesn't offer authentication, but that's not a
 * problem if our primary threat model is the accidental publication of the
 * object storage bucket. It neither has a standard file format, but without
 * practical file size limit
 * (https://crypto.stackexchange.com/questions/51518/maximum-number-of-blocks-to-be-encrypted-under-one-key-in-cbc-and-ctr-mode),
 * we can simply concatenate the IV and the ciphertext, without the need for
 * any additional record structures.
 * </p>
 * 
 * <h2>Key management</h2>
 * 
 * <p>
 * We can easily generate a new random key for each file and store it in the
 * database. This makes the key available for anyone, who would have acces to
 * the file data anyway and satisfies our threat model above. It has several
 * additional advantages:
 * <ul>
 * <li>We don't have any internal keys that we would have to manage.
 * <li>Having an own key for each file makes the encryption more resistant
 * against attacks.
 * <li>We avoid all the difficulties of asymmetric cryptography and password
 * based key derivation functions.
 * <li>This makes it trivial to offload the encryption and decryption to
 * anywhere where the access to the data is needed anyway (client app, comp,
 * session-worker).
 * </ul>
 * </p>
 * 
 * <h2>Appendix 1</h2>
 * 
 * <p>
 * Performance of the pgp file format in Java ("java encrypt pgp", "java decrypt
 * pgp") was tested on several processor architectures. The test was based on
 * example code in
 * https://github.com/bcgit/bc-java/blob/main/pg/src/main/java/org/bouncycastle/
 * , but the following changes were made to improve the troughput:
 * <ul>
 * <li>>armor: false,
 * <li>integrity: true
 * <li>CEST5 -> AES256,
 * <li>without BC provider
 * </ul>
 * </p>
 * 
 * <p>
 * "java encrypt aes" and "java encrypt aes" refer to the current
 * implementation in this classs. "native gpg encrypt" was measured with
 * GnuPG version 2.2.19 on x64 and version 2.4.3 on Apple M1:
 * <code>cat tmp/rand | gpg --output - --compress-algo none --recipient
 * RECIPIENT_NAME --always-trust --cipher-algo AES256 --encrypt - | pv >
 * tmp/rand_gpg_enc</code>
 * </p>
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
 */
public class FileEncryption {

    // other programs can't read our files anyway, so let's make it easier for us to
    // recognize them

    // v1: this signature, 16 bytes of iv and then ciphertext in
    // AES/CBC/PKCS5Padding
    public static final String CHIPSTER_ENC_SIG = "chipster-encrypted-file-v1.";

    public static final String V1_ALGORITHM = "AES/CBC/PKCS5Padding";
    public static final int V1_IV_SIZE = 16;

    private SecureRandom secureRandom = new SecureRandom();
    private KeyGenerator keyGenerator;

    public FileEncryption() throws NoSuchAlgorithmException {
        keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256);
    }

    public long getEncryptedLength(long plaintextLength) {

        /*
         * https://stackoverflow.com/a/64838079
         * 
         * A simple function to calculate the plaintext size after
         * PKCS#7 padding is applied to the message.
         * 
         * In Java PKCS#7 = PKCS#5
         * 
         * @param plaintextLength : the byte size of the plaintext
         * 
         * @return message size after the PKCS#7 padding is applied
         */
        long paddedLength = plaintextLength + 16l - (plaintextLength % 16l);

        return CHIPSTER_ENC_SIG.length() + V1_IV_SIZE + paddedLength;
    }

    public SecretKey generateKey() {

        return keyGenerator.generateKey();
    }

    public SecretKey parseKey(String key) throws DecoderException {
        return new SecretKeySpec(Hex.decodeHex(key), "AES");
    }

    public String keyToString(SecretKey secretKey) {
        return Hex.encodeHexString(secretKey.getEncoded());
    }

    public SecureRandom getSecureRandom() {
        return this.secureRandom;
    }

    public void encrypt(SecretKey secretKey, File input, File output) throws InvalidKeyException,
            NoSuchAlgorithmException, NoSuchPaddingException, InvalidAlgorithmParameterException, IOException {

        InputStream fileStream = new FileInputStream(input);
        EncryptStream encryptStream = new EncryptStream(fileStream, secretKey, this.secureRandom);
        OutputStream outputStream = new FileOutputStream(output);

        try (encryptStream; outputStream) {
            IOUtils.copyLarge(encryptStream, outputStream, new byte[1 << 16]);
        }
    }

    public void decrypt(SecretKey secretKey, File input, File output)
            throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException,
            InvalidAlgorithmParameterException, IOException, IllegalFileException {

        InputStream fileStream = new FileInputStream(input);
        DecryptStream decryptStream = new DecryptStream(fileStream, secretKey);
        OutputStream outputStream = new FileOutputStream(output);

        try (decryptStream; outputStream) {
            IOUtils.copyLarge(decryptStream, outputStream, new byte[1 << 16]);
        }
    }
}