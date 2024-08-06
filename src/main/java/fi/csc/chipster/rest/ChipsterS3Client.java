package fi.csc.chipster.rest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.s3storage.client.S3StorageClient.ByteRange;
import jakarta.ws.rs.InternalServerErrorException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncRequestBodyFromInputStreamConfiguration;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest.Builder;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Response;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * Chipster S3 client
 * 
 * Hide the actual S3 client library behind this class to make refactoring
 * easier when the library changes. Chipster uses only really basic S3
 * operations, so the library API seems to change more than Chipster's usage.
 * 
 * We could also provide a getter for the underlying S3AsyncClient, if more
 * special usage is needed.
 * 
 */
public class ChipsterS3Client {

	private final static Logger logger = LogManager.getLogger();
	private static final String CONF_TLS_VERSION = "tls-version";

	private long maxPartSize;
	private ExecutorService executor;
	private S3AsyncClient s3;

	public ChipsterS3Client(String endpoint, String region, String access, String secret, boolean pathStyleAccess) {

		this.s3 = getClient(endpoint, region, access, secret, pathStyleAccess);

		long GiB = 1024l * 1024 * 1024;
		this.maxPartSize = 4 * GiB;

		this.executor = Executors.newCachedThreadPool();
	}

	private static S3AsyncClient getClient(String endpoint, String region, String access, String secret,
			boolean pathStyleAccess) {

		AwsCredentials credentials = AwsBasicCredentials.create(access, secret);

		// default timeouts in
		// https://github.com/aws/aws-sdk-java-v2/blob/a0c8a0af1fa572b16b5bd78f310594d642324156/http-client-spi/src/main/java/software/amazon/awssdk/http/SdkHttpConfigurationOption.java#L134

		S3AsyncClient s3 = S3AsyncClient.builder()
				.region(Region.of(region))
				.endpointOverride(URI.create(endpoint))
				.credentialsProvider(StaticCredentialsProvider.create(credentials))
				.forcePathStyle(pathStyleAccess)
				.multipartEnabled(false)
				.build();

		return s3;
	}

	public List<S3Object> getObjects(String bucket) {

		ListObjectsRequest listObjects = ListObjectsRequest
				.builder()
				.bucket(bucket)
				.build();

		CompletableFuture<ListObjectsResponse> res = this.s3.listObjects(listObjects);
		return res.join().contents();
	}

	/**
	 * Configure TLS version
	 * 
	 * Option for disabling TLSv1.3, because downloads from ha-proxy (a3s.fi) fail
	 * at key update after 128 GiB.
	 * 
	 * Unfortunately AWS SDK expects this to be changed globally:
	 * https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/security-java-tls.html
	 * .
	 * 
	 * This has to be called early enough (before any use of SSLContext?) to have an
	 * effect.
	 * 
	 * @param config
	 * @param role
	 * @throws KeyManagementException
	 * @throws NoSuchAlgorithmException
	 */
	public static void configureTLSVersion(Config config, String role)
			throws KeyManagementException, NoSuchAlgorithmException {

		String tlsVersion = config.getString(CONF_TLS_VERSION, role);

		if (tlsVersion != null && !tlsVersion.isEmpty()) {

			logger.info("configure TLS version " + tlsVersion);

			// start with -Djavax.net.debug=ssl:handshake to check results
			System.setProperty("jdk.tls.client.protocols", tlsVersion);
		}
	}

	public static void checkTLSVersion(Config config, String role) throws NoSuchAlgorithmException {

		String configuredTlsVersion = config.getString(CONF_TLS_VERSION, role);
		String[] enabledVersions = SSLContext.getDefault().getDefaultSSLParameters().getProtocols();

		String joindedEnabledVersions = String.join(",", enabledVersions);

		// doesn't care about array order. May show error if multiple versions are
		// configured
		if (configuredTlsVersion != null && !configuredTlsVersion.isEmpty()
				&& !configuredTlsVersion.equals(joindedEnabledVersions)) {

			logger.error("TLS version was configured too late (most likely this has to be fixed in source code)");
			logger.error("TLS versions in configuration: " + configuredTlsVersion);
			logger.error("TLS versions in use:           " + joindedEnabledVersions);
			try {
				// make error more visible
				Thread.sleep(10_000);
			} catch (InterruptedException e) {
				// don't care
			}
		}
	}

	private CompletableFuture<CompleteMultipartUploadResponse> uploadMultipartAsync(String bucket, String key,
			InputStream inputStream, long length) {

		CompletableFuture<CompleteMultipartUploadResponse> cf = new CompletableFuture<>();

		this.executor.submit(() -> {

			try {
				CompleteMultipartUploadResponse res = uploadMultipart(bucket, key, inputStream, length);
				cf.complete(res);
			} catch (Exception e) {
				logger.error("uploadMultipartAsync failed", e);
				cf.completeExceptionally(e);
			}
		});

		return cf;
	}

	/**
	 * Upload InputStream with S3 multipart requests
	 * 
	 * aws-sdk v2 implements multipart requests already in S3AsyncClient and
	 * S3AsyncClient.crtCreate(), but both cause XAmzContentSHA256Mismatch with the
	 * current radosgw.
	 * 
	 * The same happens also here if we set the checksum algorithm at all, even if
	 * we set it to SHA256.
	 * 
	 * @param bucket
	 * @param key
	 * @param inputStream
	 * @param length
	 * @return
	 */
	private CompleteMultipartUploadResponse uploadMultipart(String bucket, String key,
			InputStream inputStream, long length) {

		// First create a multipart upload and get the upload id
		String uploadId = this.createMultipartUpload(bucket, key);

		long lengthRemaining = length;

		ArrayList<CompletedPart> completedParts = new ArrayList<CompletedPart>();

		try {
			for (int partNumber = 1; lengthRemaining > 0; partNumber++) {

				// calculate the size of the next part
				long partSize = Math.min(maxPartSize, lengthRemaining);
				lengthRemaining -= partSize;

				logger.info(
						"upload part " + partNumber + ", partSize: " + partSize + ", remaining: " + lengthRemaining);

				// create InputStream for the next part
				BoundedInputStream partInputStream = BoundedInputStream
						.builder()
						.setInputStream(inputStream)
						.setMaxCount(maxPartSize)
						.setPropagateClose(false)
						.get();

				// Upload all the different parts of the object
				CompletedPart part = this.uploadPart(bucket, key, uploadId, partNumber, partInputStream, partSize);

				completedParts.add(part);
			}

			// Finally call completeMultipartUpload operation to tell S3 to merge all
			// uploaded parts and finish the multipart operation.

			return this.completeMultipartRequest(bucket, key, uploadId, completedParts);

		} catch (CompletionException ce) {

			if (ce.getCause() instanceof S3Exception) {
				S3Exception e = (S3Exception) ce.getCause();
				logger.warn("multipart upload failed; " + e.getMessage(), e);
			}

			try {
				logger.info(
						"abort multipart upload to bucket: " + bucket + ", key: " + key);
				this.s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
						.bucket(bucket)
						.key(key)
						.uploadId(uploadId)
						.build()).join();
			} catch (Exception e) {
				logger.error("failed to abort multipart upload to bucket: " + bucket + ", key: " + key, e);
			}

			throw ce;

		} catch (IOException e) {
			logger.error("upload failed", e);
			throw new InternalServerErrorException(e);
		}
	}

	private CompleteMultipartUploadResponse completeMultipartRequest(String bucket, String key, String uploadId,
			ArrayList<CompletedPart> completedParts) {

		CompletedMultipartUpload completedMultipartUpload = CompletedMultipartUpload.builder()
				.parts(completedParts)
				.build();

		CompleteMultipartUploadRequest completeMultipartUploadRequest = CompleteMultipartUploadRequest.builder()
				.bucket(bucket)
				.key(key)
				.uploadId(uploadId)
				.multipartUpload(completedMultipartUpload)
				.build();

		return this.s3.completeMultipartUpload(completeMultipartUploadRequest).join();
	}

	private CompletedPart uploadPart(String bucket, String key, String uploadId, int partNumber,
			BoundedInputStream partInputStream, long partSize) {

		UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
				.bucket(bucket)
				.key(key)
				.uploadId(uploadId)
				.partNumber(partNumber).build();

		AsyncRequestBodyFromInputStreamConfiguration isc = AsyncRequestBodyFromInputStreamConfiguration
				.builder()
				.inputStream(partInputStream)
				.contentLength(partSize)
				.executor(this.executor)
				.build();

		String etag = this.s3
				.uploadPart(uploadPartRequest, AsyncRequestBody.fromInputStream(isc))
				.join()
				.eTag();

		return CompletedPart.builder()
				.partNumber(partNumber)
				.eTag(etag)
				.build();
	}

	private String createMultipartUpload(String bucket, String key) {

		CreateMultipartUploadRequest createMultipartUploadRequest = CreateMultipartUploadRequest.builder()
				.bucket(bucket)
				.key(key)
				.build();

		CompletableFuture<CreateMultipartUploadResponse> cResponse = this.s3
				.createMultipartUpload(createMultipartUploadRequest);

		CreateMultipartUploadResponse response = cResponse.join();
		String uploadId = response.uploadId();

		logger.debug("uploadId: " + uploadId);

		return uploadId;
	}

	/**
	 * Upload InputStream, return immediately
	 * 
	 * Caller should close the given InputStream after the returned
	 * CompletableFuture has completed.
	 */
	public CompletableFuture<? extends S3Response> uploadAsync(String bucket, String key,
			InputStream inputStream, long length) {

		if (length < maxPartSize) {
			return uploadAsyncOnePart(bucket, key, inputStream, length);
		} else {
			return uploadMultipartAsync(bucket, key, inputStream, length);
		}
	}

	private CompletableFuture<PutObjectResponse> uploadAsyncOnePart(String bucket, String key,
			InputStream inputStream, long length) {

		AsyncRequestBodyFromInputStreamConfiguration isc = AsyncRequestBodyFromInputStreamConfiguration
				.builder()
				.inputStream(inputStream)
				.contentLength(length)
				.executor(this.executor)
				.build();

		return this.s3.putObject(r -> r.bucket(bucket).key(key), AsyncRequestBody.fromInputStream(isc));
	}

	public void uploadFile(String bucket, String key, Path source)
			throws FileNotFoundException, IOException {

		try (InputStream is = new FileInputStream(source.toFile())) {
			uploadAsync(bucket, key, is, Files.size(source)).join();
		}
	}

	public CompletableFuture<GetObjectResponse> downloadFileAsync(String bucket, String key,
			File destination) {

		CompletableFuture<GetObjectResponse> cf = new CompletableFuture<>();

		this.executor.submit(() -> {
			try {
				GetObjectResponse res = downloadFile(bucket, key, destination);
				cf.complete(res);
			} catch (Exception e) {
				logger.error("downloadFileAsync failed", e);
				cf.completeExceptionally(e);
			}
		});

		return cf;
	}

	public GetObjectResponse downloadFile(String bucket, String key,
			File destination) throws IOException {

		ResponseInputStream<GetObjectResponse> is = downloadAsync(bucket, key, null).join();

		// closes the InputStream
		FileUtils.copyInputStreamToFile(is, destination);

		return is.response();
	}

	/**
	 * Get a InputStream of S3 object for downloading
	 * 
	 * Caller should close the returned InputStream after reading it.
	 * 
	 * @param bucket
	 * @param key
	 * @param range
	 * @return
	 */
	public CompletableFuture<ResponseInputStream<GetObjectResponse>> downloadAsync(String bucket, String key,
			ByteRange range) {

		Builder request = GetObjectRequest.builder()
				.bucket(bucket)
				.key(key);

		logger.debug("download range: " + range);
		if (range != null) {
			request = request.range(range.toHttpHeaderString());
		}

		return this.s3.getObject(request
				.build(),
				AsyncResponseTransformer.toBlockingInputStream());
	}

	public void deleteObject(String bucket, String key) {
		DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
				.bucket(bucket)
				.key(key)
				.build();

		CompletableFuture<DeleteObjectResponse> deletion = s3.deleteObject(deleteObjectRequest);

		deletion.join();
	}

	public boolean exists(String bucket) {
		HeadBucketRequest headBucketRequest = HeadBucketRequest.builder()
				.bucket(bucket)
				.build();

		try {
			this.s3.headBucket(headBucketRequest).join();
			return true;
		} catch (NoSuchBucketException e) {
			return false;
		}
	}

	public boolean exists(String bucket, String key) {
		try {

			HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.build();

			this.s3.headObject(headObjectRequest).join();
			return true;
		} catch (NoSuchKeyException e) {
			return false;
		}
	}

	public void close() {
		this.executor.shutdown();
	}
}
