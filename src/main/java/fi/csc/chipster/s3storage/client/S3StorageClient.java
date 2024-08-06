package fi.csc.chipster.s3storage.client;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

import org.apache.commons.codec.DecoderException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.filebroker.FileBrokerAdminResource;
import fi.csc.chipster.filebroker.StorageClient;
import fi.csc.chipster.filestorage.client.FileStorage;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.exception.ConflictException;
import fi.csc.chipster.rest.hibernate.ChipsterS3Client;
import fi.csc.chipster.s3storage.checksum.CRC32CheckedStream;
import fi.csc.chipster.s3storage.checksum.CheckedStream;
import fi.csc.chipster.s3storage.checksum.ChecksumException;
import fi.csc.chipster.s3storage.checksum.FileLengthException;
import fi.csc.chipster.s3storage.encryption.DecryptStream;
import fi.csc.chipster.s3storage.encryption.EncryptStream;
import fi.csc.chipster.s3storage.encryption.FileEncryption;
import fi.csc.chipster.s3storage.encryption.IllegalFileException;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.FileState;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Response;

/**
 * Client for accessing s3-storage files
 * 
 * This is used in file-broker to do access files in S3. It
 * uses aws-sdk library to make rquests directly to the S3 API, without going
 * through the s3-storage component (which is needed only for file deletion).
 */
public class S3StorageClient implements StorageClient {

	private final static Logger logger = LogManager.getLogger();

	// avoid too special characters, because admin API uses these in an URL path
	private static final String S3_STORAGE_ID_PREFIX = "s3_";

	private static final String CONF_S3_ENDPOINT = "s3-storage-endpoint";
	private static final String CONF_S3_REGION = "s3-storage-region";
	private static final String CONF_S3_ACCESS_KEY = "s3-storage-access-key";
	private static final String CONF_S3_SECRET_KEY = "s3-storage-secret-key";
	private static final String CONF_S3_PATH_STYLE_ACCESS = "s3-storage-path-style-access";
	private static final String CONF_S3_STORAGE_BUCKET_PREFIX = "s3-storage-bucket-";

	private Map<String, ChipsterS3Client> s3Clients;
	private Map<String, ArrayList<String>> buckets = new HashMap<>();

	private FileEncryption fileEncryption;

	private Random random = new Random();

	public S3StorageClient(Config config, String role) throws NoSuchAlgorithmException, KeyManagementException {

		ChipsterS3Client.configureTLSVersion(config, role);
		ChipsterS3Client.checkTLSVersion(config, role);

		this.s3Clients = initChipsterS3Clients(config);

		this.fileEncryption = new FileEncryption();

		for (String s3Name : this.s3Clients.keySet()) {

			ArrayList<String> buckets2 = new ArrayList<String>(
					config.getConfigEntries(CONF_S3_STORAGE_BUCKET_PREFIX + s3Name + "-").values());

			this.buckets.put(s3Name, buckets2);

			for (String bucket : buckets2) {
				logger.info("s3-storage " + s3Name + " bucket: " + bucket);
			}
		}
	}

	public ChipsterS3Client getChipsterS3Client(String s3Name) {
		return this.s3Clients.get(s3Name);
	}

	/**
	 * Get one S3Client for CLI utilities
	 * 
	 * @param config
	 * @return
	 */
	public static ChipsterS3Client getOneChipsterS3Client(Config config) {
		Map<String, ChipsterS3Client> clients = initChipsterS3Clients(config);

		if (clients.size() > 1) {
			logger.warn("multiple s3Names configured");
		}

		// get one
		return clients.values().iterator().next();
	}

	public static Map<String, ChipsterS3Client> initChipsterS3Clients(Config config) {

		Map<String, ChipsterS3Client> clients = new HashMap<>();

		// collect s3Names from all config options
		// create new set, because the keySet() doesn't support modifications
		Set<String> s3Names = new HashSet<>(config.getConfigEntries(CONF_S3_ENDPOINT + "-").keySet());
		s3Names.addAll(config.getConfigEntries(CONF_S3_REGION + "-").keySet());
		s3Names.addAll(config.getConfigEntries(CONF_S3_ACCESS_KEY + "-").keySet());
		s3Names.addAll(config.getConfigEntries(CONF_S3_SECRET_KEY + "-").keySet());
		s3Names.addAll(config.getConfigEntries(CONF_S3_PATH_STYLE_ACCESS + "-").keySet());

		for (String s3Name : s3Names) {

			String endpoint = config.getString(CONF_S3_ENDPOINT, s3Name);
			String region = config.getString(CONF_S3_REGION, s3Name);
			String access = config.getString(CONF_S3_ACCESS_KEY, s3Name);
			String secret = config.getString(CONF_S3_SECRET_KEY, s3Name);
			boolean pathStyleAccess = config.getBoolean(CONF_S3_PATH_STYLE_ACCESS);

			if (endpoint.isEmpty() || access.isEmpty() || secret.isEmpty()) {
				logger.warn("S3Storage is not configured: " + s3Name);
				continue;
			}

			logger.info("s3-storage " + s3Name + " endpoint: " + endpoint);

			ChipsterS3Client client = new ChipsterS3Client(endpoint, region, access, secret,
					pathStyleAccess);

			clients.put(s3Name, client);
		}

		return clients;
	}

	public void upload(String s3Name, String bucket, InputStream file, String objectName, Long length)
			throws InterruptedException {

		/*
		 * Require file size
		 * 
		 * aws-sdk v2 should support multipart uploads without file length, but the
		 * current radosgw wants to get a content-length for each part.
		 * 
		 * Uploads with unknown length could be implemented by buffering each part, but
		 * then we would have to use much smaller part sizes, which would limit the
		 * throughput.
		 */

		if (length == null) {
			throw new IllegalArgumentException("length cannot be null");
		}

		CompletableFuture<? extends S3Response> upload = this.s3Clients.get(s3Name).uploadAsync(bucket, objectName,
				file, length);

		try {
			upload.join();

		} catch (CompletionException ce) {
			if (ce.getCause() instanceof SdkClientException) {
				SdkClientException exception = (SdkClientException) ce.getCause();

				if (exception.getCause() instanceof FileLengthException) {
					// unwrap FileLengthException, because that's what the FileStorageClient
					throw (FileLengthException) exception.getCause();

				} else if (exception.getCause() instanceof ChecksumException) {
					throw (ChecksumException) exception.getCause();
				}
			}
			throw ce;
		}
	}

	public ResponseInputStream<GetObjectResponse> download(String s3Name, String bucket, String objectName, Long start,
			Long end)
			throws InterruptedException {

		ByteRange range = null;

		return s3Clients.get(s3Name).downloadAsync(bucket, objectName, range)
				.join();
	}

	public InputStream downloadAndDecrypt(File file, ByteRange byteRange) {

		Long start = null;
		Long end = null;
		Long plaintextEnd = null;

		logger.debug("downloadAndDecrypt byte range " + byteRange);

		if (byteRange != null) {
			logger.debug("downloadAndDecrypt byte range " + byteRange + " [" + byteRange.getStart() + ", "
					+ byteRange.getEnd() + "]");
			// because of the encryption, we have to read the file from the beginning, but
			// we can still use range queries when we don't need the whole file
			if (byteRange.getStart() == 0) {
				start = 0l;
				/*
				 * getEncryptedLength() gets the whole 16 B block. We have to get the next block
				 * too (+16) to avoid BadBaddingException. This doesn't matter, because
				 * DecryptStream can cut away the extra bytes.
				 */
				end = this.fileEncryption.getEncryptedLength(byteRange.end + 16);
				plaintextEnd = byteRange.getEnd() + 1;
			} else {
				throw new BadRequestException("start of the range must be 0");
			}
		}

		try {
			SecretKey secretKey = this.fileEncryption.parseKey(file.getEncryptionKey());
			String fileId = file.getFileId().toString();
			String bucket = storageIdToBucket(file.getStorage());
			String s3Name = storageIdToS3Name(file.getStorage());

			ResponseInputStream<GetObjectResponse> s3Stream = this.download(s3Name, bucket, fileId, start, end);
			InputStream decryptStream = new DecryptStream(s3Stream, secretKey, plaintextEnd);

			if (byteRange == null) {
				CheckedStream checksumStream = new CRC32CheckedStream(decryptStream, file.getChecksum(),
						file.getSize());

				return checksumStream;
			} else {
				logger.debug("skip checksum calculation for range request");
				// there is no point to calculate checksum in range request
				return decryptStream;
			}

		} catch (IOException | InterruptedException | NoSuchAlgorithmException | InvalidKeyException
				| NoSuchPaddingException | InvalidAlgorithmParameterException | IllegalFileException
				| DecoderException e) {
			logger.error("download failed", e);
			throw new InternalServerErrorException("download failed: " + e.getClass());
		}
	}

	public ChipsterUpload encryptAndUpload(UUID fileId, InputStream fileStream, Long length, String storageId,
			String expectedChecksum) {

		String s3Name = this.storageIdToS3Name(storageId);
		String bucket = this.storageIdToBucket(storageId);

		try {

			// new key for each file
			SecretKey secretKey = this.fileEncryption.generateKey();
			long encryptedLength = this.fileEncryption.getEncryptedLength(length);

			CheckedStream checkedStream = new CRC32CheckedStream(fileStream, expectedChecksum, length);
			EncryptStream encryptStream = new EncryptStream(checkedStream, secretKey,
					this.fileEncryption.getSecureRandom());

			this.upload(s3Name, bucket, encryptStream, fileId.toString(), encryptedLength);

			// let's store these in hex to make them easier to handle in command line tools
			String key = this.fileEncryption.keyToString(secretKey);
			String checksum = checkedStream.getStreamChecksum();

			// length of plaintext
			long fileLength = checkedStream.getLength();

			return new ChipsterUpload(fileLength, checksum, key);

		} catch (IOException | NoSuchAlgorithmException | InvalidKeyException | NoSuchPaddingException
				| InvalidAlgorithmParameterException |

				InterruptedException e) {

			logger.error("upload failed", e);
			throw new InternalServerErrorException("upload failed: " + e.getClass());
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
			logger.debug("flowTotalChunks is null, assuming one-part upload");
			return true;
		}

		if (flowTotalChunks == 1) {
			logger.debug("flowTotalChunks is 1, this is one-part upload");
			return true;
		}

		logger.debug("flowTotalChunks is " + flowTotalChunks + ", this is multipart upload");
		return false;
	}

	public boolean isEnabledForNewFiles() {
		return !this.s3Clients.isEmpty() && !this.buckets.isEmpty();
	}

	/**
	 * Select storageId for a new file
	 * 
	 * Now selected by random to spread the load. Fancier criterias can be
	 * added in the future.
	 * 
	 * @return
	 */
	public String getStorageIdForNewFile() {

		// select random s3 server
		String s3Name = new ArrayList<String>(this.buckets.keySet()).get(this.random.nextInt(this.buckets.size()));

		// select random bucket
		ArrayList<String> s3NameBuckets = this.buckets.get(s3Name);
		String bucket = s3NameBuckets.get(this.random.nextInt(s3NameBuckets.size()));

		return bucketToStorageId(s3Name, bucket);
	}

	public String bucketToStorageId(String s3Name, String bucket) {
		return S3_STORAGE_ID_PREFIX + s3Name + "_" + bucket;
	}

	public boolean containsStorageId(String storageId) {
		return storageId != null && storageId.startsWith(S3_STORAGE_ID_PREFIX);
	}

	public String storageIdToBucket(String storageId) {
		if (!containsStorageId(storageId)) {
			throw new IllegalArgumentException("not an s3 storageId: " + storageId);
		}
		return storageId.split("_")[2];
	}

	public String storageIdToS3Name(String storageId) {
		if (!containsStorageId(storageId)) {
			throw new IllegalArgumentException("not an s3 storageId: " + storageId);
		}
		return storageId.split("_")[1];
	}

	/**
	 * Parse simple byte ranges in fromat bytes=START-END
	 * 
	 * @param str
	 * @return
	 */
	public ByteRange parseByteRange(String str) {

		String BYTES_PREFIX = "bytes=";

		if (str == null) {
			return null;
		}

		if (!str.startsWith(BYTES_PREFIX)) {
			throw new BadRequestException("range must start with " + BYTES_PREFIX + str);
		}

		str = str.substring(BYTES_PREFIX.length());

		String[] values = str.split("-");
		if (values.length != 2) {
			throw new BadRequestException("wrong number of range values: " + values.length);
		}

		long start = Long.parseLong(values[0]);
		long end = Long.parseLong(values[1]);

		return new ByteRange(start, end);
	}

	public static class ByteRange {

		private Long start;
		private Long end;

		public ByteRange(long start, long end) {
			this.start = start;
			this.end = end;
		}

		public Long getStart() {
			return start;
		}

		public Long getEnd() {
			return end;
		}

		public String toString() {
			return "[" + getStart() + ", " + getEnd() + "]";
		}

		public String toHttpHeaderString() {
			return "bytes=" + getStart() + "-" + getEnd();
		}
	}

	public FileStorage[] getStorages() {
		ArrayList<FileStorage> storages = new ArrayList<>();

		for (String s3Name : buckets.keySet()) {
			for (String bucket : buckets.get(s3Name)) {
				storages.add(new FileStorage(bucketToStorageId(s3Name, bucket), null, null, false));
			}
		}

		return storages.toArray(new FileStorage[0]);
	}

	public FileEncryption getFileEncryption() {
		return this.fileEncryption;
	}

	public void delete(String storageId, UUID fileId) {

		String s3Name = storageIdToS3Name(storageId);
		String bucket = storageIdToBucket(storageId);

		this.s3Clients.get(s3Name).deleteObject(bucket, fileId.toString());
	}

	public void close() {
		for (ChipsterS3Client s3 : this.s3Clients.values()) {
			s3.close();
		}
	}

	@Override
	public InputStream download(File file, String range) {
		ByteRange byteRange = parseByteRange(range);

		return downloadAndDecrypt(file, byteRange);
	}

	@Override
	public void checkIfAppendAllowed(File file, Long chunkNumber, Long chunkSize, Long flowTotalChunks,
			Long flowTotalSize) {

		if (file.getState() == FileState.UPLOADING) {

			/*
			 * Restart paused upload
			 * 
			 * User probably has paused an upload and now continues it.
			 * This is fine for us, because we use S3 only for one-part uploads,
			 * so the app will upload the whole file again.
			 * 
			 * This doesn't save anything in the network transfers, but now the UI
			 * doesn't need to know that S3 doesn't support pause and continue.
			 */

			logger.info("upload restarted");

		} else {

			// don't allow changes to complete files
			throw new ConflictException("file exists already");
		}
	}

	@Override
	public File upload(File originalFile, InputStream fileStream, Long chunkNumber, Long chunkSize,
			Long flowTotalChunks,
			Long flowTotalSize) {

		try {
			File file = (File) originalFile.clone();

			logger.debug("upload to S3 bucket " + this.storageIdToBucket(file.getStorage()));

			// checksum is not available
			ChipsterUpload upload = this.encryptAndUpload(file.getFileId(),
					fileStream, flowTotalSize, file.getStorage(), file.getChecksum());

			file.setSize(upload.getFileLength());
			file.setChecksum(upload.getChecksum());
			file.setEncryptionKey(upload.getEncryptionKey());
			file.setState(FileState.COMPLETE);

			// let's report only errors to keep file-broker logs cleaner, for example when
			// extracting a zip-session
			logger.debug("PUT file completed, file size " + FileBrokerAdminResource.humanFriendly(file.getSize()));

			return file;

		} catch (CloneNotSupportedException e) {
			throw new RuntimeException("clone not supported", e);
		}
	}

	@Override
	public boolean deleteAfterUploadException() {
		return true;
	}

	@Override
	public void delete(File file) {
		this.delete(file.getStorage(), file.getFileId());
	}
}
