package fi.csc.chipster.s3storage.cli;

import java.io.File;
import java.io.IOException;

import fi.csc.chipster.rest.ChipsterS3Client;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.s3storage.client.S3StorageClient;

/**
 * CLI utility prgram to download file from S3
 * 
 * Sometimes it's handy to have a program which uses Chipster configuration
 * directly. Could be useful also for debugging and performance tests too.
 * 
 */
public class Download {

	public static void main(String args[]) throws InterruptedException, IOException {

		if (args.length != 3) {
			System.out.println("Usage: Download BUCKET OBJECT_KEY FILE");
			System.exit(1);
		}

		String bucket = args[0];
		String objectKey = args[1];
		File file = new File(args[2]);

		Config config = new Config();
		ChipsterS3Client s3 = S3StorageClient.getOneChipsterS3Client(config);

		download(s3, bucket, file, objectKey);
	}

	public static void download(ChipsterS3Client s3Client, String bucket, File file, String objectKey)
			throws InterruptedException, IOException {

		long t = System.currentTimeMillis();

		s3Client.downloadFile(bucket, objectKey, file);

		long dt = System.currentTimeMillis() - t;

		long fileSize = file.length();

		System.out.println(
				"download " + file.getPath() + " " + (fileSize * 1000 / dt / 1024 / 1024) + " MiB/s \t" + dt
						+ " ms \t");
	}
}