package fi.csc.chipster.s3storage;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.Checksum;

import org.apache.commons.codec.binary.Hex;

public class ChipsterChecksums {

    public static String checksum(String filename, Checksum crc) throws IOException, NoSuchAlgorithmException {
        InputStream in = new BufferedInputStream(new FileInputStream(filename));

        byte[] buffer = new byte[1024];
        int numRead;

        do {
            numRead = in.read(buffer);
            if (numRead > 0) {
                crc.update(buffer, 0, numRead);
            }
        } while (numRead != -1);

        in.close();
        return Long.toHexString(crc.getValue());
    }

    public static String messageDigest(String filename, String algorithm) throws IOException, NoSuchAlgorithmException {
        InputStream in = new BufferedInputStream(new FileInputStream(filename));

        MessageDigest md = MessageDigest.getInstance(algorithm);
        byte[] buffer = new byte[1024];
        int numRead;

        do {
            numRead = in.read(buffer);
            if (numRead > 0) {
                md.update(buffer, 0, numRead);
            }
        } while (numRead != -1);

        in.close();

        return Hex.encodeHexString(md.digest());
    }
}