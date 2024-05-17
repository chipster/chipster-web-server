package fi.csc.chipster.s3storage.cli;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

import fi.csc.chipster.s3storage.benchmark.ChecksumBenchmark;

public class Checksum {

        public static void main(String[] args) throws IOException, NoSuchAlgorithmException {

                if (args.length != 2) {
                        System.out.println("Usage: Checksum CHECKSUM_TYPE FILE");
                        System.out.println("  CHECKSUM_TYPE: CRC32, CRC32C, MD5, SHA-512");
                        System.exit(1);
                }

                String type = args[0];
                File file = new File(args[1]);

                if ("CRC32".equals(type)) {
                        ChecksumBenchmark.test(new CRC32(), null, file);
                } else if ("CRC32C".equals(type)) {
                        ChecksumBenchmark.test(new CRC32C(), null, file);
                } else {
                        ChecksumBenchmark.test(null, type, file);
                }
        }
}