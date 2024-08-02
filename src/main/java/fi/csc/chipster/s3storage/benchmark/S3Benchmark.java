package fi.csc.chipster.s3storage.benchmark;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import org.apache.commons.io.FileUtils;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.S3Util;
import fi.csc.chipster.s3storage.client.S3StorageClient;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.Transfer;

/**
 * Test the performance of S3 file transfer using aws-sdk library in Java
 */
public class S3Benchmark {

	public static void test(S3TransferManager tm, String bucket, String name, boolean isUpload, boolean isSerial,
			int count, String fileName) throws InterruptedException {

		long t = System.currentTimeMillis();

		List<Transfer> transfers = new ArrayList<>();

		for (int i = 0; i < count; i++) {

			Transfer transfer = null;

			if (isUpload) {
				// same file is fine for uploads
				File file = new File(fileName);

				transfer = S3Util.uploadFileAsync(tm, bucket, "rand_4k_" + i, file.toPath());
			} else {
				File file = new File(fileName + i + ".s3");

				transfer = S3Util.downloadFileAsync(tm, bucket, "rand_4k_" + i, file.toPath());
			}

			if (isSerial) {
				try {
					transfer.completionFuture().join();
				} catch (CompletionException e) {
					if (e.getCause() instanceof S3Exception) {
						S3Exception s3e = (S3Exception) e.getCause();
						System.out.println("status code: " + s3e.statusCode());
						System.out.println("message: " + s3e.getMessage());
						System.out.println("error details: " + s3e.awsErrorDetails());
					}
					throw e;
				}
			} else {
				transfers.add(transfer);
			}
		}

		if (!isSerial) {
			for (Transfer transfer : transfers) {
				transfer.completionFuture().join();
			}
		}

		long dt = System.currentTimeMillis() - t;

		long fileSize = 0;
		if (isUpload) {
			fileSize = new File(fileName).length();
		} else {
			fileSize = new File(fileName + 0 + ".s3").length();
		}

		String serialType = isSerial ? "serial" : "parallel";
		String transferType = isUpload ? "upload" : "download";

		System.out.println(name + " " + serialType + " " + transferType + " " + count + " file(s) \t"
				+ (count * 1000 / dt) + " ops/s \t"
				+ (fileSize * count * 1000 / dt / 1024 / 1024) + " MiB/s \t" + dt + " ms \t");
	}

	public static void main(String args[]) throws InterruptedException, IOException {

		// long largeFileSize = 1l * 1024 * 1024 * 1024;
		// int smallFilesCount = 100;
		long largeFileSize = 10l * 1024 * 1024;
		// long largeFileSize = 128l * 1024 * 1024;
		int smallFilesCount = 10;

		File tmpDir = BenchmarkData.generateTestFiles(largeFileSize, smallFilesCount, new ArrayList<File>());
		String tmpDirString = tmpDir.getPath();

		Config config = new Config();
		S3TransferManager tm = S3StorageClient.getOneTransferManager(config);

		String bucket = "s3-file-broker-test";

		test(tm, bucket, "warm-up", true, true, smallFilesCount, tmpDirString + "/rand_4k_0");

		test(tm, bucket, "4k", true, true, smallFilesCount, tmpDirString + "/rand_4k_0");

		test(tm, bucket, "4k", true, false, smallFilesCount, tmpDirString + "/rand_4k_0");

		test(tm, bucket, "4k", false, true, smallFilesCount, tmpDirString + "/rand_4k_");

		test(tm, bucket, "4k", false, false, smallFilesCount, tmpDirString + "/rand_4k_");

		test(tm, bucket, "large", true, true, 1, tmpDirString + "/rand");

		test(tm, bucket, "large", false, true, 1, tmpDirString + "/rand");

		test(tm, bucket, "large", true, false, 4, tmpDirString + "/rand");

		test(tm, bucket, "large", false, false, 4, tmpDirString + "/rand");

		FileUtils.deleteDirectory(tmpDir);
	}
}