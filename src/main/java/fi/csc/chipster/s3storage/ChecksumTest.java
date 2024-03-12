package fi.csc.chipster.s3storage;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

import org.apache.commons.codec.binary.Hex;

public class ChecksumTest {

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

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {

        String filename = "tmp/rand";
        long size = new File(filename).length();

        System.out.println("CRC32:");
        long start = System.currentTimeMillis();
        String checksum = checksum(filename, new CRC32());
        long end = System.currentTimeMillis();
        System.out.println(checksum);
        System.out.println(
                (end - start) + " milliseconds " + (size / 1024.0 / 1024) / ((end - start) / 1000.0) + " MB/s");

        System.out.println("CRC32C:");
        start = System.currentTimeMillis();
        checksum = checksum(filename, new CRC32C());
        end = System.currentTimeMillis();
        System.out.println(checksum);
        System.out.println(
                (end - start) + " milliseconds " + (size / 1024.0 / 1024) / ((end - start) / 1000.0) + " MB/s");

        System.out.println("MD5:");
        start = System.currentTimeMillis();
        checksum = messageDigest(filename, "MD5");
        end = System.currentTimeMillis();
        System.out.println(checksum);
        System.out.println(
                (end - start) + " milliseconds " + (size / 1024.0 / 1024) / ((end - start) / 1000.0) + " MB/s");

        System.out.println("SHA512:");
        start = System.currentTimeMillis();
        checksum = messageDigest(filename, "SHA-512");
        end = System.currentTimeMillis();
        System.out.println(checksum);
        System.out.println(
                (end - start) + " milliseconds " + (size / 1024.0 / 1024) / ((end - start) / 1000.0) + " MB/s");
    }
}