package fi.csc.chipster.s3storage.benchmark;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import fi.csc.chipster.s3storage.checksum.ChecksumStream;

public class ChecksumBenchmark {

        public static void main(String[] args) throws IOException, NoSuchAlgorithmException {

                File tmpDir = BenchmarkData.generateTestFiles(4l * 1024 * 1024 * 1024, 0, null);

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
                        checksumString = checksum(testFile, checksum);
                } else {
                        checksumString = messageDigest(testFile, digest);
                }

                long end = System.currentTimeMillis();
                System.out.println(checksumString);
                System.out.println(
                                (end - start) + " ms " + (1000 * size / 1024 / 1024) / ((end - start))
                                                + " MB/s");
        }

        public static String checksum(File testFile, Checksum crc) throws IOException, NoSuchAlgorithmException {

                /*
                 * Test the performance of the ChecksumStream
                 * 
                 * A simple loop (below) would be a lot faster (3200 MiB/s vs. 1600 MiB/s on
                 * M1), but this is good enough
                 */
                FileInputStream fileStream = new FileInputStream(testFile);
                ChecksumStream checksumStream = new ChecksumStream(fileStream, null, crc);

                try (checksumStream) {
                        IOUtils.copyLarge(checksumStream, OutputStream.nullOutputStream(), new byte[1 << 16]);
                }

                return checksumStream.getStreamChecksum();

                // InputStream in = new BufferedInputStream(new FileInputStream(testFile));
                // byte[] buffer = new byte[1024];
                // int numRead;

                // do {
                // numRead = in.read(buffer);
                // if (numRead > 0) {
                // crc.update(buffer, 0, numRead);
                // }
                // } while (numRead != -1);

                // in.close();
                // return Long.toHexString(crc.getValue());
        }

        public static String messageDigest(File testFile, String algorithm)
                        throws IOException, NoSuchAlgorithmException {

                // We don't have InputStream for this yet
                InputStream in = new BufferedInputStream(new FileInputStream(testFile));

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