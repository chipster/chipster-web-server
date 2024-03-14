package fi.csc.chipster.s3storage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.TransferManager;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.S3Util;

public class S3Test {

	private final static Logger logger = LogManager.getLogger();

	private static final String CONF_S3_ENDPOINT = "file-broker-s3-endpoint";
	private static final String CONF_S3_REGION = "file-broker-s3-region";
	private static final String CONF_S3_ACCESS_KEY = "file-broker-s3-access-key";
	private static final String CONF_S3_SECRET_KEY = "file-broker-s3-secret-key";
	private static final String COND_S3_SIGNER_OVERRIDE = "file-broker-s3-signer-override";

	public static TransferManager getTransferManager(Config config, String role) {
		String endpoint = config.getString(CONF_S3_ENDPOINT, role);
		String region = config.getString(CONF_S3_REGION, role);
		String access = config.getString(CONF_S3_ACCESS_KEY, role);
		String secret = config.getString(CONF_S3_SECRET_KEY, role);
		String signerOverride = config.getString(COND_S3_SIGNER_OVERRIDE, role);

		if (endpoint == null || region == null || access == null || secret == null) {
			logger.warn("backups are not configured");
		}

		return S3Util.getTransferManager(endpoint, region, access, secret, signerOverride);
	}

	public static void test(TransferManager tm, String bucket, String name, boolean isUpload, boolean isSerial,
			int count,
			String fileName) throws InterruptedException {
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

	public static void main(String args[]) throws InterruptedException {

		Config config = new Config();
		TransferManager tm = getTransferManager(config, Role.FILE_BROKER);

		File outFile = new File("tmp/rand_4k_1.s3");
		String bucket = "s3-file-broker-test";
		// String object = "rand_4k_1";

		int smallFilesCount = 300;
		// int smallFilesCount = 10;

		try {

			test(tm, bucket, "4k", true, true, smallFilesCount, "tmp/rand_4k_0");

			test(tm, bucket, "4k", true, false, smallFilesCount, "tmp/rand_4k_0");

			test(tm, bucket, "4k", false, true, smallFilesCount, "tmp/rand_4k_");

			test(tm, bucket, "4k", false, false, smallFilesCount, "tmp/rand_4k_");

			test(tm, bucket, "large", true, true, 1, "tmp/rand");

			test(tm, bucket, "large", false, true, 1, "tmp/rand");

			test(tm, bucket, "large", true, false, 4, "tmp/rand");

			test(tm, bucket, "large", false, false, 4, "tmp/rand");

		} catch (AmazonServiceException e) {
			e.printStackTrace();
			System.err.println(e.getErrorMessage());
			System.exit(1);
		}
		tm.shutdownNow();
	}
}