package fi.csc.chipster.s3storage.cli;

import java.io.File;
import java.io.IOException;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.S3Util;
import fi.csc.chipster.s3storage.client.S3StorageClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

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
		S3TransferManager tm = S3StorageClient.getOneTransferManager(config);

		download(tm, bucket, file, objectKey);
	}

	public static void download(S3TransferManager tm, String bucket, File file, String objectKey)
			throws InterruptedException {

		long t = System.currentTimeMillis();

		S3Util.downloadFile(tm, bucket, objectKey, file.toPath());

		long dt = System.currentTimeMillis() - t;

		long fileSize = file.length();

		System.out.println(
				"download " + file.getPath() + " " + (fileSize * 1000 / dt / 1024 / 1024) + " MiB/s \t" + dt
						+ " ms \t");
	}
}