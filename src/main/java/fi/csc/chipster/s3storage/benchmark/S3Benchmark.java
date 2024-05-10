package fi.csc.chipster.s3storage.benchmark;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.TransferManager;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.s3storage.client.S3StorageClient;

/**
 * Test the performance of S3 file transfer using aws-sdk library in Java
 */
public class S3Benchmark {

	public static void test(TransferManager tm, String bucket, String name, boolean isUpload, boolean isSerial,
			int count, String fileName) throws InterruptedException {

		long t = System.currentTimeMillis();

		List<Transfer> transfers = new ArrayList<>();

		for (int i = 0; i < count; i++) {

			Transfer transfer = null;

			if (isUpload) {
				// same file is fine for uploads
				File file = new File(fileName);
				transfer = tm.upload(bucket, "rand_4k_" + i, file);
			} else {
				File file = new File(fileName + i + ".s3");
				transfer = tm.download(bucket, "rand_4k_" + i, file);
			}

			if (isSerial) {
				AmazonClientException exception = transfer.waitForException();
				if (exception != null) {
					throw exception;
				}
			} else {
				transfers.add(transfer);
			}
		}

		if (!isSerial) {
			for (Transfer transfer : transfers) {
				AmazonClientException exception = transfer.waitForException();
				if (exception != null) {
					throw exception;
				}
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
		long largeFileSize = 128l * 1024 * 1024;
		int smallFilesCount = 10;

		File tmpDir = BenchmarkData.generateTestFiles(largeFileSize, smallFilesCount, new ArrayList<File>());
		String tmpDirString = tmpDir.getPath();

		Config config = new Config();
		TransferManager tm = S3StorageClient.getOneTransferManager(config);

		String bucket = "s3-file-broker-test";

		try {

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

		} catch (AmazonServiceException e) {
			e.printStackTrace();
			System.err.println(e.getErrorMessage());
			System.exit(1);
		}
		tm.shutdownNow();
	}
}