package fi.csc.chipster.s3storage.tests;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

import org.apache.commons.io.FileUtils;

import fi.csc.chipster.s3storage.ChipsterChecksums;

public class ChecksumTest {

        public static void main(String[] args) throws IOException, NoSuchAlgorithmException {

                File tmpDir = TestData.generateTestFiles(4l * 1024 * 1024 * 1024, 0, null);

                File testFile = new File(tmpDir, "rand");

                test(new CRC32(), null, testFile);
                test(new CRC32C(), null, testFile);
                test(null, "MD5", testFile);
                test(null, "SHA-512", testFile);

                FileUtils.deleteDirectory(tmpDir);
        }

        public static void test(Checksum checksum, String digest, File testFile)
                        throws NoSuchAlgorithmException, IOException {

                long size = testFile.length();

                if (checksum != null) {
                        System.out.println(checksum.getClass().getSimpleName());
                } else {
                        System.out.println(digest);
                }

                String checksumString = null;

                long start = System.currentTimeMillis();
                if (checksum != null) {
                        checksumString = ChipsterChecksums.checksum(testFile, checksum);
                } else {
                        checksumString = ChipsterChecksums.messageDigest(testFile, digest);
                }

                long end = System.currentTimeMillis();
                System.out.println(checksumString);
                System.out.println(
                                (end - start) + " ms " + (1000 * size / 1024 / 1024) / ((end - start))
                                                + " MB/s");
        }
}