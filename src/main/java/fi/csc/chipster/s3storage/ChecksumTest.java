package fi.csc.chipster.s3storage;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

public class ChecksumTest {

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {

        String filename = "tmp/rand";
        long size = new File(filename).length();

        System.out.println("CRC32:");
        long start = System.currentTimeMillis();
        String checksum = ChipsterChecksums.checksum(filename, new CRC32());
        long end = System.currentTimeMillis();
        System.out.println(checksum);
        System.out.println(
                (end - start) + " milliseconds " + (size / 1024.0 / 1024) / ((end - start) / 1000.0) + " MB/s");

        System.out.println("CRC32C:");
        start = System.currentTimeMillis();
        checksum = ChipsterChecksums.checksum(filename, new CRC32C());
        end = System.currentTimeMillis();
        System.out.println(checksum);
        System.out.println(
                (end - start) + " milliseconds " + (size / 1024.0 / 1024) / ((end - start) / 1000.0) + " MB/s");

        System.out.println("MD5:");
        start = System.currentTimeMillis();
        checksum = ChipsterChecksums.messageDigest(filename, "MD5");
        end = System.currentTimeMillis();
        System.out.println(checksum);
        System.out.println(
                (end - start) + " milliseconds " + (size / 1024.0 / 1024) / ((end - start) / 1000.0) + " MB/s");

        System.out.println("SHA512:");
        start = System.currentTimeMillis();
        checksum = ChipsterChecksums.messageDigest(filename, "SHA-512");
        end = System.currentTimeMillis();
        System.out.println(checksum);
        System.out.println(
                (end - start) + " milliseconds " + (size / 1024.0 / 1024) / ((end - start) / 1000.0) + " MB/s");
    }
}