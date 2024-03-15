package fi.csc.chipster.s3storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.zip.CRC32;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.TransferManager;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.S3Util;
import fi.csc.chipster.sessiondb.model.Dataset;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;

public class S3StorageClient {

	private final static Logger logger = LogManager.getLogger();

	private static final String CONF_S3_ENDPOINT = "file-broker-s3-endpoint";
	private static final String CONF_S3_REGION = "file-broker-s3-region";
	private static final String CONF_S3_ACCESS_KEY = "file-broker-s3-access-key";
	private static final String CONF_S3_SECRET_KEY = "file-broker-s3-secret-key";
	private static final String COND_S3_SIGNER_OVERRIDE = "file-broker-s3-signer-override";

	private TransferManager transferManager;

	private FileEncryption fileEncryption;

	public S3StorageClient(Config config) throws NoSuchAlgorithmException {
		this.transferManager = getTransferManager(config, Role.FILE_BROKER);
		this.fileEncryption = new FileEncryption();
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

	public InputStream downloadEncrypted(Dataset dataset) {

		try {

			java.io.File tmpFile = Files.createTempFile("chipster-s3-upload-temp", ".enc").toFile();
			java.io.File decryptedFile = Files.createTempFile("chipster-s3-upload-temp", ".dec").toFile();
			this.download("s3-file-broker-test", dataset.getFile().getFileId().toString(), tmpFile);

			SecretKey key = this.fileEncryption.parseKey(dataset.getFile().getEncryptionKey());

			this.fileEncryption.decrypt(key, tmpFile, decryptedFile);

			String checksum = ChipsterChecksums.checksum(decryptedFile.getPath(), new CRC32());

			if (dataset.getFile().getChecksum() != null) {
				if (checksum.equals(dataset.getFile().getChecksum())) {
					logger.info("checksum ok: " + checksum);
				} else {
					throw new InternalServerErrorException("checksum error");
				}
			} else {
				logger.info("checksum not available");
			}

			FileInputStream fileStream = new FileInputStream(decryptedFile);

			return fileStream;
		} catch (IOException | InterruptedException | NoSuchAlgorithmException | InvalidKeyException
				| NoSuchPaddingException | InvalidAlgorithmParameterException | IllegalBlockSizeException
				| BadPaddingException | IllegalFileException | DecoderException e) {
			logger.error("download failed", e);
			throw new InternalServerErrorException("download failed: " + e.getClass());
		}
	}

	public ChipsterUpload uploadEncrypted(UUID fileId, InputStream fileStream) {
		try {
			java.io.File tmpFile = Files.createTempFile("chipster-s3-upload-temp", "").toFile();
			java.io.File encryptedFile = Files.createTempFile("chipster-s3-upload-temp", ".enc").toFile();

			FileUtils.copyInputStreamToFile(fileStream, tmpFile);

			// fast plaintext checksum to detect bugs. Definitely not cryptographically
			// secure
			String checksum = ChipsterChecksums.checksum(tmpFile.getPath(), new CRC32());

			// new key for each file
			SecretKey secretKey = this.fileEncryption.generateKey();
			this.fileEncryption.encrypt(secretKey, tmpFile, encryptedFile);

			// let's store these in hex to make them easier to handle in command line tools
			String key = Hex.encodeHexString(secretKey.getEncoded());

			this.upload("s3-file-broker-test", encryptedFile, fileId.toString());

			// length of plaintext
			long fileLength = tmpFile.length();

			tmpFile.delete();
			encryptedFile.delete();

			return new ChipsterUpload(fileLength, checksum, key);

		} catch (IOException | NoSuchAlgorithmException | InvalidKeyException | NoSuchPaddingException
				| InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException
				| InterruptedException e) {

			throw new InternalServerErrorException("upload failed" + e.getClass());
		}
	}

	public static class ChipsterUpload {
		public ChipsterUpload(long fileLength, String checksum, String key) {
			this.fileLength = fileLength;
			this.checksum = checksum;
			this.encryptionKey = key;
		}

		public String checksum;
		public long fileLength;
		public String encryptionKey;

		public long getFileLength() {
			return fileLength;
		}

		public String getChecksum() {
			return checksum;
		}

		public String getEncryptionKey() {
			return encryptionKey;
		}
	}
}
