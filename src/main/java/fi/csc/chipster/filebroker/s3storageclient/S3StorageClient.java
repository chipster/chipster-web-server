package fi.csc.chipster.filebroker.s3storageclient;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;

import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.TransferManager;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.S3Util;
import fi.csc.chipster.s3storage.ChecksumStream;
import fi.csc.chipster.s3storage.DecryptStream;
import fi.csc.chipster.s3storage.EncryptStream;
import fi.csc.chipster.s3storage.FileEncryption;
import fi.csc.chipster.s3storage.FileLengthException;
import fi.csc.chipster.s3storage.IllegalFileException;
import fi.csc.chipster.sessiondb.model.Dataset;
import jakarta.ws.rs.InternalServerErrorException;

public class S3StorageClient {

	private final static Logger logger = LogManager.getLogger();

	private static final String S3_STORAGE_ID_PREFIX = "s3-bucket://";

	private static final String CONF_S3_ENDPOINT = "file-broker-s3-endpoint";
	private static final String CONF_S3_REGION = "file-broker-s3-region";
	private static final String CONF_S3_ACCESS_KEY = "file-broker-s3-access-key";
	private static final String CONF_S3_SECRET_KEY = "file-broker-s3-secret-key";
	private static final String COND_S3_SIGNER_OVERRIDE = "file-broker-s3-signer-override";
	private static final String CONF_S3_STORAGE_BUCKET_PREFIX = "file-broker-s3-bucket-";

	private TransferManager transferManager;

	private FileEncryption fileEncryption;

	private ArrayList<String> buckets;

	private Random random = new Random();

	public S3StorageClient(Config config) throws NoSuchAlgorithmException {
		this.transferManager = getTransferManager(config, Role.FILE_BROKER);
		this.fileEncryption = new FileEncryption();

		this.buckets = new ArrayList<String>(
				config.getConfigEntries(CONF_S3_STORAGE_BUCKET_PREFIX).values());
		// remove the empty value from chipste-defaults.yaml
		this.buckets.remove("");
	}

	public static TransferManager getTransferManager(Config config, String role) {
		String endpoint = config.getString(CONF_S3_ENDPOINT, role);
		String region = config.getString(CONF_S3_REGION, role);
		String access = config.getString(CONF_S3_ACCESS_KEY, role);
		String secret = config.getString(CONF_S3_SECRET_KEY, role);
		String signerOverride = config.getString(COND_S3_SIGNER_OVERRIDE, role);

		if (endpoint == null || access == null || secret == null) {
			logger.warn("S3Storage is not configured");
			return null;
		}

		return S3Util.getTransferManager(endpoint, region, access, secret, signerOverride);
	}

	public void upload(String bucket, InputStream file, String objectName, long length)
			throws InterruptedException {

		Transfer transfer = null;

		// TransferManager can handle multipart uploads, but requires a file length in
		// advance. The lower lever api would support uploads without file length, but
		// then we have to take care of multipart uploads ourselves
		ObjectMetadata objMeta = new ObjectMetadata();
		objMeta.setContentLength(this.fileEncryption.getEncryptedLength(length));

		transfer = this.transferManager.upload(bucket, objectName, file, objMeta);

		AmazonClientException exception = transfer.waitForException();
		if (exception != null) {
			throw exception;
		}
	}

	public S3ObjectInputStream download(String bucket, String objectName)
			throws InterruptedException {

		return transferManager.getAmazonS3Client().getObject(bucket, objectName).getObjectContent();
	}

	public InputStream downloadAndDecrypt(Dataset dataset) {

		try {
			SecretKey secretKey = this.fileEncryption.parseKey(dataset.getFile().getEncryptionKey());
			String fileId = dataset.getFile().getFileId().toString();
			String bucket = storageIdToBucket(dataset.getFile().getStorage());

			S3ObjectInputStream s3Stream = this.download(bucket, fileId);
			InputStream decryptStream = new DecryptStream(s3Stream, secretKey);
			ChecksumStream checksumStream = new ChecksumStream(decryptStream, dataset.getFile().getChecksum());

			return checksumStream;

		} catch (IOException | InterruptedException | NoSuchAlgorithmException | InvalidKeyException
				| NoSuchPaddingException | InvalidAlgorithmParameterException | IllegalFileException
				| DecoderException e) {
			logger.error("download failed", e);
			throw new InternalServerErrorException("download failed: " + e.getClass());
		}
	}

	public ChipsterUpload encryptAndUpload(UUID fileId, InputStream fileStream, Long length, String storageId) {

		String bucket = this.storageIdToBucket(storageId);

		try {

			// new key for each file
			SecretKey secretKey = this.fileEncryption.generateKey();

			CountingInputStream countingInputStream = new CountingInputStream(fileStream);
			ChecksumStream checksumStream = new ChecksumStream(countingInputStream, null);
			EncryptStream encryptStream = new EncryptStream(checksumStream, secretKey,
					this.fileEncryption.getSecureRandom());

			this.upload(bucket, encryptStream, fileId.toString(), length);

			// let's store these in hex to make them easier to handle in command line tools
			String key = this.fileEncryption.keyToString(secretKey);
			String checksum = checksumStream.getStreamChecksum();

			if (length == null) {
				// shouldn't happen, because upload() requires length for now
				logger.info("original file lentgh not avalaible");
			} else if (length.longValue() != countingInputStream.getByteCount()) {
				throw new FileLengthException(
						"file was supposed to be " + length + " bytes, but was " + countingInputStream.getByteCount());
			}

			// length of plaintext
			long fileLength = countingInputStream.getByteCount();

			return new ChipsterUpload(fileLength, checksum, key);

		} catch (IOException | NoSuchAlgorithmException | InvalidKeyException | NoSuchPaddingException
				| InvalidAlgorithmParameterException | InterruptedException e) {

			logger.error("upload failed", e);
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

	public boolean isOnePartUpload(Long flowTotalChunks) {

		if (flowTotalChunks == null) {
			logger.info("flowTotalChunks is null, assuming one-part upload");
			return true;
		}

		if (flowTotalChunks == 1) {
			logger.info("flowTotalChunks is 1, this is one-part upload");
			return true;
		}

		logger.info("flowTotalChunks is " + flowTotalChunks + ", this is multipart upload");
		return false;
	}

	public boolean isEnabled() {
		return this.transferManager != null;
	}

	public String getStorageIdForNewFile() {
		/*
		 * StorageId for S3 files
		 * 
		 * Let's assume there is only one s3 server for now. Maybe it's better not to
		 * save the name of the S3 endpoint in strorageId, in case it nees to be
		 * changed.
		 */
		String bucket = this.buckets.get(this.random.nextInt(this.buckets.size()));
		return S3_STORAGE_ID_PREFIX + bucket;
	}

	public boolean containsStorageId(String storageId) {
		return storageId != null && storageId.startsWith(S3_STORAGE_ID_PREFIX);
	}

	public String storageIdToBucket(String storageId) {
		if (!containsStorageId(storageId)) {
			throw new IllegalArgumentException("not an s3 storageId: " + storageId);
		}
		return storageId.substring(S3_STORAGE_ID_PREFIX.length());
	}
}
