package fi.csc.chipster.s3storage;

import java.io.File;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.TransferManager;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.S3Util;

public class S3StorageClient {

	private final static Logger logger = LogManager.getLogger();

	private static final String CONF_S3_ENDPOINT = "file-broker-s3-endpoint";
	private static final String CONF_S3_REGION = "file-broker-s3-region";
	private static final String CONF_S3_ACCESS_KEY = "file-broker-s3-access-key";
	private static final String CONF_S3_SECRET_KEY = "file-broker-s3-secret-key";
	private static final String COND_S3_SIGNER_OVERRIDE = "file-broker-s3-signer-override";

	private TransferManager transferManager;

	public S3StorageClient(Config config) {
		this.transferManager = getTransferManager(config, Role.FILE_BROKER);
	}

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

	public void upload(String bucket, File file, String objectName)
			throws InterruptedException {

		long t = System.currentTimeMillis();

		Transfer transfer = null;

		transfer = this.transferManager.upload(bucket, objectName, file);

		AmazonClientException exception = transfer.waitForException();
		if (exception != null) {
			throw exception;
		}

		long dt = System.currentTimeMillis() - t;

		long fileSize = file.length();

		System.out.println(
				"uploaded " + fileSize / 1024 / 1024 + " MiB " + (fileSize * 1000 / dt / 1024 / 1024) + " MiB/s ");
	}

	public void download(String bucket, String objectName, File file)
			throws InterruptedException {

		long t = System.currentTimeMillis();

		Transfer transfer = null;

		transfer = this.transferManager.download(bucket, objectName, file);

		AmazonClientException exception = transfer.waitForException();
		if (exception != null) {
			throw exception;
		}

		long dt = System.currentTimeMillis() - t;

		long fileSize = file.length();

		System.out.println(
				"downloaded " + fileSize / 1024 / 1024 + " MiB " + (fileSize * 1000 / dt / 1024 / 1024) + " MiB/s ");
	}
}
