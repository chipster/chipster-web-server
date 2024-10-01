package fi.csc.chipster.s3storage.cli;

import java.io.File;
import java.util.ArrayList;

import fi.csc.chipster.s3storage.benchmark.BenchmarkData;
import io.jsonwebtoken.io.IOException;

/**
 * CLI utility program to generate test files
 */
public class GenerateTestData {
    public static void main(String args[]) throws IOException, java.io.IOException {

        if (args.length != 2) {
            System.out.println("Usage: GenerateTestData LARGE_FILE_SIZE_MiB SMALL_FILE_COUNT");
            System.exit(1);
        }

        long largeFileSize = Long.parseLong(args[0]);
        int smallFileCount = Integer.parseInt(args[1]);

        File tmpDir = BenchmarkData.generateTestFiles(largeFileSize * 1024 * 1024, smallFileCount,
                new ArrayList<File>());

        System.out.println(tmpDir.getPath());
    }
}
