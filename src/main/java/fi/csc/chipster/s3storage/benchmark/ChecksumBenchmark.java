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

import fi.csc.chipster.s3storage.checksum.CheckedStream;

/**
 * Test the performance of different checksum algorithms in Java
 * 
 * There are big throughput differences between different checksum algorithms on
 * different architectures. This benchmark can be used to make sure the checksum
 * calculation won't become a bottleneck in file transfers.
 * 
 * Results (with an earlier implementation without nested InputStreams, current
 * implementation tops out around 1.5 GiB/s):
 * 
 * Checksum performance on M1:
 * 
 * CRC32: 2400 MB/s
 * CRC32C: 2600 MB/s
 * MD5: 500 MB/s
 * SHA-512: 1000 MB/s
 * 
 * Checksum performance on Intel Skylake:
 * 
 * CRC32: 554 MB/s
 * CRC32C: 1595 MB/s
 * MD5: 358 MB/s
 * SHA-512: 295 MB/s
 * 
 * Checksum performance on AMD EPYC:
 * 
 * CRC32: 3000 MB/s
 * CRC32C: 3900 MB/s
 * MD5: 549 MB/s
 * SHA-512: 485 MB/s
 */
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
                CheckedStream checksumStream = new CheckedStream(fileStream, null, crc, null);

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