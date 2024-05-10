package fi.csc.chipster.s3storage.cli;

import java.io.File;
import java.io.IOException;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.TransferManager;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.s3storage.client.S3StorageClient;

/**
 * CLI utility program to upload a file to S3
 * 
 * Sometimes it's handy to have a program which uses Chipster configuration
 * directly. Could be useful also for debugging and performance tests too.
 */
public class Upload {

	public static void main(String args[]) throws InterruptedException, IOException {

		if (args.length != 3) {
			System.out.println("Usage: Upload FILE BUCKET OBJECT_KEY");
			System.exit(1);
		}

		File file = new File(args[0]);
		String bucket = args[1];
		String objectKey = args[2];

		Config config = new Config();
		TransferManager tm = S3StorageClient.getOneTransferManager(config);

		try {

			upload(tm, bucket, file, objectKey);

		} catch (AmazonServiceException e) {
			e.printStackTrace();
			System.err.println(e.getErrorMessage());
			System.exit(1);
		}
		tm.shutdownNow();
	}

	public static void upload(TransferManager tm, String bucket, File file, String objectKey)
			throws InterruptedException {

		long t = System.currentTimeMillis();

		Transfer transfer = tm.upload(bucket, objectKey, file);

		AmazonClientException exception = transfer.waitForException();
		if (exception != null) {
			throw exception;
		}

		long dt = System.currentTimeMillis() - t;

		long fileSize = file.length();

		System.out.println(
				"upload " + file.getPath() + " " + (fileSize * 1000 / dt / 1024 / 1024) + " MiB/s \t" + dt + " ms \t");
	}
}