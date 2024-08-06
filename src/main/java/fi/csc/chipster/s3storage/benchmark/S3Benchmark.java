package fi.csc.chipster.s3storage.benchmark;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.io.FileUtils;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.ChipsterS3Client;
import fi.csc.chipster.s3storage.client.S3StorageClient;
import software.amazon.awssdk.services.s3.model.S3Response;

/**
 * Test the performance of S3 file transfer using aws-sdk library in Java
 */
public class S3Benchmark {

	public static void test(ChipsterS3Client s3Client, String bucket, String name, boolean isUpload,
			boolean isSerial,
			int count, ArrayList<File> uploadFiles, ArrayList<String> objects, ArrayList<File> downloadFiles)
			throws InterruptedException, IOException {

		long t = System.currentTimeMillis();

		List<CompletableFuture<? extends S3Response>> transfers = new ArrayList<>();
		List<InputStream> inputStreams = new ArrayList<>();

		long bytes = 0l;

		for (int i = 0; i < count; i++) {

			File uploadFile = uploadFiles.get(i);
			File downloadFile = downloadFiles.get(i);
			String s3Key = objects.get(i);

			CompletableFuture<? extends S3Response> transfer = null;

			if (isUpload) {

				FileInputStream inputStream = new FileInputStream(uploadFile);
				transfer = s3Client.uploadAsync(bucket, s3Key, inputStream,
						Files.size(uploadFile.toPath()));
				inputStreams.add(inputStream);
			} else {
				transfer = s3Client.downloadFileAsync(bucket, s3Key, downloadFile);
			}

			if (isSerial) {

				transfer.join();

			} else {
				transfers.add(transfer);
			}

			bytes += uploadFile.length();
		}

		if (!isSerial) {
			for (CompletableFuture<? extends S3Response> transfer : transfers) {
				transfer.join();
			}
		}

		for (InputStream is : inputStreams) {
			is.close();
		}

		long dt = System.currentTimeMillis() - t;

		// check and remove results after download, (time not included)
		if (!isUpload) {
			for (int i = 0; i < count; i++) {

				File uploadFile = uploadFiles.get(i);
				File downloadFile = downloadFiles.get(i);
				if (!FileUtils.contentEquals(uploadFile, downloadFile)) {
					throw new IllegalStateException(
							"files differ after upload and download: " + uploadFile + " " + downloadFile);
				}

				downloadFile.delete();
			}
		}

		String serialType = isSerial ? "serial" : "parallel";
		String transferType = isUpload ? "upload" : "download";

		System.out.println(name + " " + serialType + " " + transferType + " " + count + " file(s) \t"
				+ (count * 1000 / dt) + " ops/s \t"
				+ (bytes * 1000 / dt / 1024 / 1024) + " MiB/s \t" + dt + " ms \t");
	}

	public static void main(String args[]) throws InterruptedException, IOException {

		// long largeFileSize = 6l * 1024 * 1024 * 1024;
		// int smallFilesCount = 1000;
		long largeFileSize = 128l * 1024 * 1024;
		int smallFilesCount = 10;

		File tmpDir = BenchmarkData.generateTestFiles(largeFileSize, smallFilesCount, new ArrayList<File>());
		String tmpDirString = tmpDir.getPath();

		ArrayList<File> smallUploadFiles = new ArrayList<>();
		ArrayList<File> largeUploadFiles = new ArrayList<>();

		ArrayList<File> smallDownloadFiles = new ArrayList<>();
		ArrayList<File> largeDownloadFiles = new ArrayList<>();

		ArrayList<String> smallObjects = new ArrayList<>();
		ArrayList<String> largeObjects = new ArrayList<>();

		for (int i = 0; i < smallFilesCount; i++) {
			smallUploadFiles.add(new File(tmpDirString + "/rand_4k_" + i));
			smallDownloadFiles.add(new File(tmpDirString + "/rand_4k_" + i + ".s3"));
			smallObjects.add("rand_4k_" + i);
		}

		for (int i = 0; i < 4; i++) {
			// same input file for all uploads
			largeUploadFiles.add(new File(tmpDirString + "/rand"));
			largeDownloadFiles.add(new File(tmpDirString + "/rand_" + i));
			largeObjects.add("rand_" + i);
		}

		Config config = new Config();

		ChipsterS3Client s3 = S3StorageClient.getOneChipsterS3Client(config);

		String bucket = "s3-file-broker-test";

		test(s3, bucket, "warm-up", true, true, smallFilesCount, smallUploadFiles, smallObjects, smallDownloadFiles);

		test(s3, bucket, "4k", true, true, smallFilesCount, smallUploadFiles, smallObjects, smallDownloadFiles);

		test(s3, bucket, "4k", true, false, smallFilesCount, smallUploadFiles, smallObjects, smallDownloadFiles);

		test(s3, bucket, "4k", false, true, smallFilesCount, smallUploadFiles, smallObjects, smallDownloadFiles);

		test(s3, bucket, "4k", false, false, smallFilesCount, smallUploadFiles, smallObjects, smallDownloadFiles);

		test(s3, bucket, "large", true, true, 1, largeUploadFiles, largeObjects, largeDownloadFiles);

		test(s3, bucket, "large", false, true, 1, largeUploadFiles, largeObjects, largeDownloadFiles);

		test(s3, bucket, "large", true, false, 4, largeUploadFiles, largeObjects, largeDownloadFiles);

		test(s3, bucket, "large", false, false, 4, largeUploadFiles, largeObjects, largeDownloadFiles);

		FileUtils.deleteDirectory(tmpDir);

		s3.close();
	}
}