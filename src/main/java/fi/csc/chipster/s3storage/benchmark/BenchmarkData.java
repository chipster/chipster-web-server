package fi.csc.chipster.s3storage.benchmark;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.apache.commons.io.IOUtils;

import io.jsonwebtoken.io.IOException;

/**
 * Generate a some test data for the benchmarks in this package
 */
public class BenchmarkData {
    public static File generateTestFiles(long fileSize, long smallFileCount, List<File> smallTestFiles)
            throws IOException, java.io.IOException {

        File tmpDir = Files.createTempDirectory(Paths.get("."), "chipsterThroughputTestTmp").toFile();

        File largeTestFile = new File(tmpDir, "rand");

        // generate test data

        System.out.println("copy " + fileSize / 1024 / 1024 + " MB to " + largeTestFile);
        try (InputStream is = new FileInputStream("/dev/urandom");
                OutputStream os = new FileOutputStream(new File(tmpDir, "rand"))) {

            IOUtils.copyLarge(is, os, 0, fileSize, new byte[64 * 1024]);
        }

        if (smallTestFiles != null) {

            for (int i = 0; i < smallFileCount; i++) {
                File testFile = new File(tmpDir, "rand_4k_" + i);
                smallTestFiles.add(testFile);
            }

            System.out.println("copy " + smallFileCount + " 4k files");

            for (File testFile : smallTestFiles) {
                try (InputStream is = new FileInputStream("/dev/urandom");
                        OutputStream os = new FileOutputStream(testFile)) {

                    IOUtils.copyLarge(is, os, 0, 4 * 1024, new byte[4 * 1024]);
                }
            }
        }

        return tmpDir;
    }
}
