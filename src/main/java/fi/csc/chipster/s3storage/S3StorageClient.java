package fi.csc.chipster.s3storage;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
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
import fi.csc.chipster.sessiondb.model.Dataset;
import jakarta.ws.rs.InternalServerErrorException;

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

	public void upload(String bucket, InputStream file, String objectName, long length)
			throws InterruptedException {

		Transfer transfer = null;

		// TransferManager can handle multipart uploads, but requires a file length in
		// advance the lower lever api would support uploads without file length, but
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
			SecretKey key = this.fileEncryption.parseKey(dataset.getFile().getEncryptionKey());
			String fileId = dataset.getFile().getFileId().toString();

			S3ObjectInputStream s3Stream = this.download("s3-file-broker-test", fileId);
			InputStream decryptStream = new DecryptStream(s3Stream, key);
			ChecksumStream checksumStream = new ChecksumStream(decryptStream, dataset.getFile().getChecksum());

			return checksumStream;

		} catch (IOException | InterruptedException | NoSuchAlgorithmException | InvalidKeyException
				| NoSuchPaddingException | InvalidAlgorithmParameterException | IllegalFileException
				| DecoderException e) {
			logger.error("download failed", e);
			throw new InternalServerErrorException("download failed: " + e.getClass());
		}
	}

	public ChipsterUpload uploadEncrypted(UUID fileId, InputStream fileStream, long length) {

		try {

			// new key for each file
			SecretKey secretKey = this.fileEncryption.generateKey();

			CountingInputStream countinInputStream = new CountingInputStream(fileStream);
			ChecksumStream checksumStream = new ChecksumStream(countinInputStream, null);
			EncryptStream encryptStream = new EncryptStream(checksumStream, secretKey,
					this.fileEncryption.getSecureRandom());

			this.upload("s3-file-broker-test", encryptStream, fileId.toString(), length);

			// let's store these in hex to make them easier to handle in command line tools
			String key = Hex.encodeHexString(secretKey.getEncoded());
			String checksum = checksumStream.getStreamChecksum();

			// length of plaintext
			long fileLength = countinInputStream.getByteCount();

			return new ChipsterUpload(fileLength, checksum, key);

		} catch (IOException | NoSuchAlgorithmException | InvalidKeyException | NoSuchPaddingException
				| InvalidAlgorithmParameterException | InterruptedException e) {

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
